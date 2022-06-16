import os
import time
import socket
import struct
import hashlib
from pathlib import Path
from enum import Enum
import numpy as np


class OP(Enum):
    RRQ = 1
    WRQ = 2
    DAT = 3
    ACK = 4
    ERR = 5


class TFTPClient:
    def __init__(self, remote, basedir):
        self.remote = remote
        self.basedir = Path(basedir)

    def createRequest(self, op, fn, mode=b'octet'):
        return struct.pack('!H', op.value) + b'%b\x00%b\x00' % (fn, mode)

    def createRequestBadOp(self, op, fn, mode=b'octet'):
        return struct.pack('!H', op) + b'%b\x00%b\x00' % (fn, mode)

    def createACK(self, bn):
        return struct.pack('!HH', OP.ACK.value, bn)

    def createDATBuf(self, buf, bn):
        return struct.pack('!HH', OP.DAT.value, bn) + buf

    def parsePacket(self, buf):
        rv = {'op': OP(struct.unpack('!H', buf[0:2])[0])}

        if rv['op'] == OP.ACK:
            rv['bn'] = struct.unpack('!H', buf[2:4])[0]
        if rv['op'] == OP.DAT:
            rv['bn'] = struct.unpack('!H', buf[2:4])[0]
            rv['data'] = buf[4:]
        if rv['op'] == OP.ERR:
            rv['code'] = struct.unpack('!H', buf[2:4])[0]
            rv['msg'] = str(buf[4:-1], 'utf-8')

        return rv

    def fileBufEq(self, fn, buf):
        fc = open(self.basedir / os.fsdecode(fn), 'rb').read()

        fc_h = hashlib.sha256()
        fc_h.update(fc)

        buf_h = hashlib.sha256()
        buf_h.update(buf)

        return fc_h.digest() == buf_h.digest()

    def newSocket(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.settimeout(10)

        return sock

    def checkACK(self, op, e):
        if op != OP.ACK:
            raise ValueError(f'Opcode should be ACK is {op}.')
        if e:
            raise ValueError(f'Invalid block number.')

    def getFile(self, fn, mode=b'octet'):
        buf = b''

        with self.newSocket() as sock:
            req = self.createRequest(OP.RRQ, fn, mode)
            sock.sendto(req, self.remote)

            ebn = 1
            while True:
                resp, ca = sock.recvfrom(1024)

                pkt = self.parsePacket(resp)
                if pkt['op'] != OP.DAT:
                    raise ValueError(f'Opcode should be DAT, is {pkt["op"]}.')
                if pkt['bn'] != ebn:
                    raise ValueError(f'Block num should be {ebn}, is {pkt["bn"]}.')

                buf += pkt['data']
                req = self.createACK(pkt['bn'])
                sock.sendto(req, ca)

                if len(pkt['data']) < 512:
                    break

                ebn += 1

            if not self.fileBufEq(fn, buf):
                raise ValueError('File and buffer are not the same.')

        return True

    def getMultiBlockFileFailAck(self, fn, n, mode=b'octet'):
        buf = b''
        with self.newSocket() as sock:
            req = self.createRequest(OP.RRQ, fn, mode)
            sock.sendto(req, self.remote)

            ebn = 1
            while True:
                for i in range(n + 1):
                    resp, ca = sock.recvfrom(1024)
                    pkt = self.parsePacket(resp)

                    if pkt['op'] != OP.DAT:
                        raise ValueError(f'Opcode should be DAT is {pkt["op"]}.')
                    if pkt['bn'] != ebn:
                        raise ValueError(f'Block num should be {ebn} is {pkt["bn"]}.')
                    if i != n:
                        continue

                    buf += pkt['data']
                    req = self.createACK(pkt['bn'])
                    sock.sendto(req, ca)

                if len(pkt['data']) < 512:
                    break

                ebn += 1

            if not self.fileBufEq(fn, buf):
                raise ValueError('Files are not the same.')

        return True

    def getFileNotExists(self, fn, mode=b'octet'):
        with self.newSocket() as sock:
            req = self.createRequest(OP.RRQ, fn, mode)
            sock.sendto(req, self.remote)
            resp, ca = sock.recvfrom(1024)
            pkt = self.parsePacket(resp)

            if pkt['op'] != OP.ERR:
                raise ValueError(f'Expected OP.ERR got {pkt["op"]}')
            if pkt['code'] != 1:
                raise ValueError(f'Expected error code 1 got {pkt["code"]}')

        return True

    def sendBadOp(self, op, mode=b'octet'):
        with self.newSocket() as sock:
            req = self.createRequestBadOp(op, b'fn', mode)
            sock.sendto(req, self.remote)
            try:
                resp, ca = sock.recvfrom(1024)
                pkt = self.parsePacket(resp)
                if pkt['op'] != OP.ERR:
                    raise ValueError(f'Expected OP.ERR got {pkt["op"]}.')
                if pkt['code'] != 4:
                    raise ValueError(f'Expected error code 4 got {pkt["code"]}.')
            except socket.timeout:
                pass  # Ok to not respond to a bad request

        return True

    def putFileBytes(self, fn, sz, mode=b'octet'):
        with self.newSocket() as sock:
            req = self.createRequest(OP.WRQ, fn, mode)
            sock.sendto(req, self.remote)
            resp, ca = sock.recvfrom(1024)

            pkt = self.parsePacket(resp)
            self.checkACK(pkt['op'], pkt['bn'] != 0)

            sbuf = np.random.bytes(sz)
            req = self.createDATBuf(sbuf, 1)
            sock.sendto(req, ca)
            try:
                resp, ca = sock.recvfrom(1024)
                pkt = self.parsePacket(resp)
                self.checkACK(pkt['op'], pkt['bn'] != 1)
            except socket.timeout:
                raise ValueError('Timeout waiting for ACK.')
        
        time.sleep(5)
        if not self.fileBufEq(fn, sbuf):
            raise ValueError('Files are not the same.')

        return True

    def putFileBlocks(self, fn, sz, mode=b'octet'):
        fc = b''
        with self.newSocket() as sock:
            req = self.createRequest(OP.WRQ, fn, mode)
            sock.sendto(req, self.remote)
            resp, ca = sock.recvfrom(1024)

            pkt = self.parsePacket(resp)
            self.checkACK(pkt['op'], pkt['bn'] != 0)

            for blk in range(1, sz + 1):
                sbuf = np.random.bytes(512)
                req = self.createDATBuf(sbuf, blk)
                sock.sendto(req, ca)
                try:
                    resp, ca = sock.recvfrom(1024)
                    pkt = self.parsePacket(resp)
                    self.checkACK(pkt['op'], pkt['bn'] != blk)
                except socket.timeout:
                    raise ValueError('Timeout waiting for ACK.')

                fc += sbuf

            req = self.createDATBuf(b'', sz + 1)
            sock.sendto(req, ca)

            try:
                resp, ca = sock.recvfrom(1024)
                pkt = self.parsePacket(resp)
                self.checkACK(pkt['op'], pkt['bn'] != sz + 1)
            except socket.timeout:
                raise ValueError('Timeout waiting for ACK.')

        time.sleep(5)
        if not self.fileBufEq(fn, fc):
            raise ValueError('Files are not the same.')

        return True

import pytest
import os

# Init client
@pytest.fixture(scope="module")
def client():
    import tftpclient
    return tftpclient.TFTPClient(('localhost', 9000), os.getcwd() + '/tftpserverdir')


# Get existing 50 byte file 1 √
def test_GSBSmall(client):
    assert client.getFile(b'f50b.bin')


# Get existing 500 byte file 2 √
def test_GSBLarge(client):
    assert client.getFile(b'f500b.bin')


# Get existing 1,535 byte file 3 √
def test_GMB3(client):
    assert client.getFile(b'f3blks.bin')


# Get existing 262,143 byte file 4 √
def test_GMB512(client):
    assert client.getFile(b'f512blks.bin')


# Put 50 byte file 5 √
def test_PSB50B(client):
    assert client.putFileBytes(b'f50b.ul', 50)


# Put 500 byte file 6 √
def test_PSB500B(client):
    assert client.putFileBytes(b'f500b.ul', 500)


# Put 512 byte file 7 √
def test_PMB1Blks(client):
    assert client.putFileBlocks(b'f1blk.ul', 1)


# Put 1,536 byte file 8
def test_PMB3Blks(client):
    assert client.putFileBlocks(b'f3blks.ul', 3)


# Put 262,144 byte file 9
def test_PMB512Blks(client):
    assert client.putFileBlocks(b'f512blks.ul', 512)


# Try to get a file that does not exist 10 √
def test_GFileNotExists(client):
    assert client.getFileNotExists(b'nosuchfile')


# Send unknown request type 11 √
def test_BadOp10(client):
    assert client.sendBadOp(10)


# Send an unknown request type (similar to an existing) 12 √
def test_BadOp257(client):
    assert client.sendBadOp(257)


# Get a large file and fail the first ACK every time 13 √
def test_GMBFail1stAck(client):
    assert client.getMultiBlockFileFailAck(b'f3blks.bin', 1)


# Get a large file and fail the first two ACKs every time 14
def test_GMBFail2ndAck(client):
    assert client.getMultiBlockFileFailAck(b'f3blks.bin', 2)

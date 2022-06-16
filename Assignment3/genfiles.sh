#!/bin/bash

dd if=/dev/urandom of=f50b.bin bs=1 count=50
dd if=/dev/urandom of=f500b.bin bs=1 count=500
dd if=/dev/urandom of=f3blks.bin bs=1 count=1535
dd if=/dev/urandom of=f512blks.bin bs=1 count=262143

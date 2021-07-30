Digital Signal Processing Utilities
=======================================================

## Overview

This repository contains some useful digital signal processing utilities, some of them are written to support radar data receivers which compromise interface in  `LVD data + valid line` form :

* `AsyncQueueWithMem` - changed version of `AsyncQueue` available inside rocket-chip.
* `AsyncQueuePackerLazyModule` - block which uses `AsyncQueueWithMem` and concatenates LVDS lines in order to generate 64 bits `AXI4` stream
* `AsyncQueueWithCrcLine` - block which uses `AsyncQueueWithMem` with 17 bit input and 17 bit output (16 bits are LVDS data, MSB bit is `crc_on_line` bit)
* `AsyncQueueAXI4StreamOut` - input is LVDS data + valid line, output is AXI4 stream
*  `AsyncQueueModule` - simple test module
* `DspQueueWithSyncReadMem` - It implements simple queue with custom parameters, it can support programmable full feature, useful for debugging purposes
* `QueueWithSyncReadMem` - just a copy of Queue from the newest Chisel, currently not available in `chipyard`

**Important Note:**
This project needs to be run with `chipyard` dependencies due to some specific relations that `AsyncQueue` requires to have.

## TODO
* Find a way how to easily test multiple clock domains (consult required)
* Update documentation
* Add more test cases

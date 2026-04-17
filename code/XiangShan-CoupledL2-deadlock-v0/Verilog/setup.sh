#!/bin/bash
path=$1
time=24
threads=16
max_jobs=32
if [ $# -lt 1 ]
then
    echo "Usage: ./setup.sh filepath [time=0] [threads=16] [max_jobs=32]"
    echo "  time: set_prove_time_limit \${time}h"
    echo "  threads: set_engine_threads \${threads}"
    exit
fi

if [ $# -gt 1 ]
then
    time=$2
    if [ $# -gt 2 ]
    then
        threads=$3
        if [ $# -gt 3 ]
        then
            max_jobs=$4
        fi
    fi
fi

echo "if {[catch {analyze -sv ${path} LogPerfHelper.v ResetCounter.sv STD_CLKGT_func.v ClockGate.v TLLogWriter.v} err]} {" > verify.tcl
echo "    exit 1" >> verify.tcl
echo "}" >> verify.tcl
echo "if {[catch {elaborate -bbox_a 300000} err]} {" >> verify.tcl
echo "    exit 1" >> verify.tcl
echo "}" >> verify.tcl
echo "reset reset" >> verify.tcl
echo "clock clock" >> verify.tcl
if [ $time -gt 0 ]
then
    echo "set_prove_time_limit ${time}h" >> verify.tcl
else
    echo "set_prove_time_limit 0s" >> verify.tcl
fi
echo "set_engine_threads ${threads}" >> verify.tcl
echo "set_proofgrid_per_engine_max_jobs ${max_jobs}" >> verify.tcl
echo "report" >> verify.tcl
echo "prove -all" >> verify.tcl
echo "report" >> verify.tcl
jg -allow_unsupported_OS -tcl verify.tcl -no_gui

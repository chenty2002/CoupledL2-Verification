#!/bin/bash
set -euo pipefail

if [ "$#" -lt 1 ] || [ "$#" -gt 4 ]; then
    echo "Usage: ./setup.sh filepath [time=24] [threads=16] [max_jobs=32]" >&2
    exit 1
fi
path=$1
if [ ! -s "$path" ]; then
    echo "Missing or empty SystemVerilog top: $path" >&2
    exit 1
fi
for file in LogPerfHelper.v ResetCounter.sv STD_CLKGT_func.v ClockGate.v TLLogWriter.v; do
    if [ ! -f "$file" ]; then
        echo "Missing verification support file: $file" >&2
        exit 1
    fi
done
command -v jg >/dev/null 2>&1 || { echo "Missing required command: jg" >&2; exit 1; }
time=24
threads=16
max_jobs=32
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

echo "if {[catch {analyze -sv {${path}} LogPerfHelper.v ResetCounter.sv STD_CLKGT_func.v ClockGate.v TLLogWriter.v} err]} {" > verify.tcl
echo "    exit 1" >> verify.tcl
echo "}" >> verify.tcl
echo "if {[catch {elaborate -bbox_a 300000} err]} {" >> verify.tcl
echo "    exit 1" >> verify.tcl
echo "}" >> verify.tcl
echo "reset reset" >> verify.tcl
echo "clock clock" >> verify.tcl
if [ "$time" -gt 0 ]
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

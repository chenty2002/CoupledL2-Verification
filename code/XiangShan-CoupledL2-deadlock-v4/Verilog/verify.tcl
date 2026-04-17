analyze -sv VerifyTop_performance.sv LogPerfHelper.v ResetCounter.sv STD_CLKGT_func.v ClockGate.v TLLogWriter.v
elaborate
reset reset
clock clock
set_prove_time_limit 0s
set_engine_threads 16
set_proofgrid_per_engine_max_jobs 32

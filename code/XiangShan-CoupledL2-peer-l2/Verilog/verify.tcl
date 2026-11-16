analyze -sv VerifyTop_mutual.sv LogPerfHelper.v ResetCounter.sv STD_CLKGT_func.v ClockGate.v TLLogWriter.v
elaborate -bbox_a 300000
reset reset
clock clock
set_prove_time_limit 48h
set_engine_threads 16
set_proofgrid_per_engine_max_jobs 32

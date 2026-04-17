if {[catch {analyze -sv VerifyTop_accquire.sv LogPerfHelper.v ResetCounter.sv STD_CLKGT_func.v ClockGate.v TLLogWriter.v} err]} {
    exit 1
}
if {[catch {elaborate -bbox_a 300000} err]} {
    exit 1
}
reset reset
clock clock
set_prove_time_limit 0s
set_engine_threads 16
set_proofgrid_per_engine_max_jobs 32

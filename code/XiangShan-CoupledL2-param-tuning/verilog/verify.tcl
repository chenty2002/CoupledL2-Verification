analyze -sv12 VerifyTop.sv ../../LogPerfHelper.v ../../ResetCounter.sv ../../STD_CLKGT_func.v ../../ClockGate.v ../../TLLogWriter.v
elaborate -bbox_a 65536
reset reset
clock clock
set_prove_time_limit 1800s
set_engine_threads 4
set_proofgrid_per_engine_max_jobs 4

set assertions {}
set covers {}
set manifest [open property_manifest.tsv w]
set excluded [open excluded_properties.tsv w]
foreach property [get_property_list] {
    array set info [get_property_info $property]
    if {[regexp {param_} $property]} {
        puts $manifest [join [list $property $info(command) $info(status)] "\t"]
        if {$info(command) eq "assert"} {lappend assertions $property}
        if {$info(command) eq "cover"} {lappend covers $property}
    } else {
        puts $excluded [join [list $property $info(command)] "\t"]
    }
}
close $manifest
close $excluded

set required {param_way_index param_way_isolation param_unique_live_tag param_address_decode param_block_boundary param_block_size param_beat_shape param_beat_stable param_block_assembly param_mshr_capacity param_mshr_unique param_mshr_progress param_ram_lanes param_ram_data}
foreach family $required {
    set found 0
    foreach property $assertions {
        if {[regexp "${family}(__|$)" $property]} {set found 1}
    }
    if {!$found} {error "missing parameter property family: $family"}
}
if {[llength $covers] == 0} {error "missing parameter cover properties"}

set prove_result [prove -property [concat $assertions $covers]]
set statuses [open property_status.tsv w]
puts $statuses "prove_result\t$prove_result"
foreach property [concat $assertions $covers] {
    array set info [get_property_info $property]
    puts $statuses [join [list $property $info(command) $info(status) $info(run_status)] "\t"]
}
close $statuses
report -results -file report.txt

module CacheTop(
  input         clock,
  input         reset,
  input         inputAValid,
  input         inputDReady,
  input  [2:0]  inputRandomOpcode,
  input  [15:0] inputRandomAddress
);


wire         dut_out_port_0_0_a_ready;
wire         dut_in_port_0_0_a_valid;
wire [2:0]   dut_in_port_0_0_a_bits_opcode;
wire [2:0]   dut_in_port_0_0_a_bits_param;
wire [2:0]   dut_in_port_0_0_a_bits_size;
wire [4:0]   dut_in_port_0_0_a_bits_source;
wire [15:0]  dut_in_port_0_0_a_bits_address;
wire [1:0]   dut_in_port_0_0_a_bits_user_alias;
wire         dut_in_port_0_0_a_bits_mask;
wire [7:0]   dut_in_port_0_0_a_bits_data;
wire         dut_in_port_0_0_a_bits_corrupt;
wire         dut_in_port_0_0_b_ready;
wire         dut_out_port_0_0_b_valid;
wire [2:0]   dut_out_port_0_0_b_bits_opcode;
wire [1:0]   dut_out_port_0_0_b_bits_param;
wire [2:0]   dut_out_port_0_0_b_bits_size;
wire [4:0]   dut_out_port_0_0_b_bits_source;
wire [15:0]  dut_out_port_0_0_b_bits_address;
wire         dut_out_port_0_0_b_bits_mask;
wire [7:0]   dut_out_port_0_0_b_bits_data;
wire         dut_out_port_0_0_b_bits_corrupt;
wire         dut_out_port_0_0_c_ready;
wire         dut_in_port_0_0_c_valid;
wire [2:0]   dut_in_port_0_0_c_bits_opcode;
wire [2:0]   dut_in_port_0_0_c_bits_param;
wire [2:0]   dut_in_port_0_0_c_bits_size;
wire [4:0]   dut_in_port_0_0_c_bits_source;
wire [15:0]  dut_in_port_0_0_c_bits_address;
wire [1:0]   dut_in_port_0_0_c_bits_user_alias;
wire [7:0]   dut_in_port_0_0_c_bits_data;
wire         dut_in_port_0_0_c_bits_corrupt;
wire         dut_in_port_0_0_d_ready;
wire         dut_out_port_0_0_d_valid;
wire [2:0]   dut_out_port_0_0_d_bits_opcode;
wire [1:0]   dut_out_port_0_0_d_bits_param;
wire [2:0]   dut_out_port_0_0_d_bits_size;
wire [4:0]   dut_out_port_0_0_d_bits_source;
wire [7:0]   dut_out_port_0_0_d_bits_sink;
wire         dut_out_port_0_0_d_bits_denied;
wire [7:0]   dut_out_port_0_0_d_bits_data;
wire         dut_out_port_0_0_d_bits_corrupt;
wire         dut_out_port_0_0_e_ready;
wire         dut_in_port_0_0_e_valid;
wire [7:0]   dut_in_port_0_0_e_bits_sink;


Agent L1D(  
  .clock(clock),
  .reset(reset),
  .io_inputAValid(inputAValid),
  .io_inputDReady(inputDReady),
  .io_inputRandomOpcode(inputRandomOpcode),
  .io_inputRandomAddress(inputRandomAddress),
  
  .io_a_ready(dut_out_port_0_0_a_ready),
  .io_a_valid(dut_in_port_0_0_a_valid),
  .io_a_bits_opcode(dut_in_port_0_0_a_bits_opcode),
  .io_a_bits_param(dut_in_port_0_0_a_bits_param),
  .io_a_bits_size(dut_in_port_0_0_a_bits_size),
  .io_a_bits_source(dut_in_port_0_0_a_bits_source),
  .io_a_bits_address(dut_in_port_0_0_a_bits_address),
  .io_a_bits_user_alias(dut_in_port_0_0_a_bits_user_alias),
  .io_a_bits_mask(dut_in_port_0_0_a_bits_mask),
  .io_a_bits_data(dut_in_port_0_0_a_bits_data),
  .io_a_bits_corrupt(dut_in_port_0_0_a_bits_corrupt),
  .io_b_ready(dut_in_port_0_0_b_ready),
  .io_b_valid(dut_out_port_0_0_b_valid),
  .io_b_bits_opcode(dut_out_port_0_0_b_bits_opcode),
  .io_b_bits_param(dut_out_port_0_0_b_bits_param),
  .io_b_bits_size(dut_out_port_0_0_b_bits_size),
  .io_b_bits_source(dut_out_port_0_0_b_bits_source),
  .io_b_bits_address(dut_out_port_0_0_b_bits_address),
  .io_b_bits_mask(dut_out_port_0_0_b_bits_mask),
  .io_b_bits_data(dut_out_port_0_0_b_bits_data),
  .io_b_bits_corrupt(dut_out_port_0_0_b_bits_corrupt),
  .io_c_ready(dut_out_port_0_0_c_ready),
  .io_c_valid(dut_in_port_0_0_c_valid),
  .io_c_bits_opcode(dut_in_port_0_0_c_bits_opcode),
  .io_c_bits_param(dut_in_port_0_0_c_bits_param),
  .io_c_bits_size(dut_in_port_0_0_c_bits_size),
  .io_c_bits_source(dut_in_port_0_0_c_bits_source),
  .io_c_bits_address(dut_in_port_0_0_c_bits_address),
  .io_c_bits_user_alias(dut_in_port_0_0_c_bits_user_alias),
  .io_c_bits_data(dut_in_port_0_0_c_bits_data),
  .io_c_bits_corrupt(dut_in_port_0_0_c_bits_corrupt),
  .io_d_ready(dut_in_port_0_0_d_ready),
  .io_d_valid(dut_out_port_0_0_d_valid),
  .io_d_bits_opcode(dut_out_port_0_0_d_bits_opcode),
  .io_d_bits_param(dut_out_port_0_0_d_bits_param),
  .io_d_bits_size(dut_out_port_0_0_d_bits_size),
  .io_d_bits_source(dut_out_port_0_0_d_bits_source),
  .io_d_bits_sink(dut_out_port_0_0_d_bits_sink),
  .io_d_bits_denied(dut_out_port_0_0_d_bits_denied),
  .io_d_bits_data(dut_out_port_0_0_d_bits_data),
  .io_d_bits_corrupt(dut_out_port_0_0_d_bits_corrupt),
  .io_e_ready(dut_out_port_0_0_e_ready),
  .io_e_valid(dut_in_port_0_0_e_valid),
  .io_e_bits_sink(dut_in_port_0_0_e_bits_sink)
);


// port ready and valid?
TestTop L2L3(
  .clock(clock),
  .reset(reset),
  .master_port_0_0_a_ready(dut_out_port_0_0_a_ready),
  .master_port_0_0_a_valid(dut_in_port_0_0_a_valid),
  .master_port_0_0_a_bits_opcode(dut_in_port_0_0_a_bits_opcode),
  .master_port_0_0_a_bits_param(dut_in_port_0_0_a_bits_param),
  .master_port_0_0_a_bits_size(dut_in_port_0_0_a_bits_size),
  .master_port_0_0_a_bits_source(dut_in_port_0_0_a_bits_source),
  .master_port_0_0_a_bits_address(dut_in_port_0_0_a_bits_address),
  .master_port_0_0_a_bits_user_alias(dut_in_port_0_0_a_bits_user_alias),
  .master_port_0_0_a_bits_mask(dut_in_port_0_0_a_bits_mask),
  .master_port_0_0_a_bits_data(dut_in_port_0_0_a_bits_data),
  .master_port_0_0_a_bits_corrupt(dut_in_port_0_0_a_bits_corrupt),
  .master_port_0_0_b_ready(dut_in_port_0_0_b_ready),
  .master_port_0_0_b_valid(dut_out_port_0_0_b_valid),
  .master_port_0_0_b_bits_opcode(dut_out_port_0_0_b_bits_opcode),
  .master_port_0_0_b_bits_param(dut_out_port_0_0_b_bits_param),
  .master_port_0_0_b_bits_size(dut_out_port_0_0_b_bits_size),
  .master_port_0_0_b_bits_source(dut_out_port_0_0_b_bits_source),
  .master_port_0_0_b_bits_address(dut_out_port_0_0_b_bits_address),
  .master_port_0_0_b_bits_mask(dut_out_port_0_0_b_bits_mask),
  .master_port_0_0_b_bits_data(dut_out_port_0_0_b_bits_data),
  .master_port_0_0_b_bits_corrupt(dut_out_port_0_0_b_bits_corrupt),
  .master_port_0_0_c_ready(dut_out_port_0_0_c_ready),
  .master_port_0_0_c_valid(dut_in_port_0_0_c_valid),
  .master_port_0_0_c_bits_opcode(dut_in_port_0_0_c_bits_opcode),
  .master_port_0_0_c_bits_param(dut_in_port_0_0_c_bits_param),
  .master_port_0_0_c_bits_size(dut_in_port_0_0_c_bits_size),
  .master_port_0_0_c_bits_source(dut_in_port_0_0_c_bits_source),
  .master_port_0_0_c_bits_address(dut_in_port_0_0_c_bits_address),
  .master_port_0_0_c_bits_user_alias(dut_in_port_0_0_c_bits_user_alias),
  .master_port_0_0_c_bits_data(dut_in_port_0_0_c_bits_data),
  .master_port_0_0_c_bits_corrupt(dut_in_port_0_0_c_bits_corrupt),
  .master_port_0_0_d_ready(dut_in_port_0_0_d_ready),
  .master_port_0_0_d_valid(dut_out_port_0_0_d_valid),
  .master_port_0_0_d_bits_opcode(dut_out_port_0_0_d_bits_opcode),
  .master_port_0_0_d_bits_param(dut_out_port_0_0_d_bits_param),
  .master_port_0_0_d_bits_size(dut_out_port_0_0_d_bits_size),
  .master_port_0_0_d_bits_source(dut_out_port_0_0_d_bits_source),
  .master_port_0_0_d_bits_sink(dut_out_port_0_0_d_bits_sink),
  .master_port_0_0_d_bits_denied(dut_out_port_0_0_d_bits_denied),
  .master_port_0_0_d_bits_data(dut_out_port_0_0_d_bits_data),
  .master_port_0_0_d_bits_corrupt(dut_out_port_0_0_d_bits_corrupt),
  .master_port_0_0_e_ready(dut_out_port_0_0_e_ready),
  .master_port_0_0_e_valid(dut_in_port_0_0_e_valid),
  .master_port_0_0_e_bits_sink(dut_in_port_0_0_e_bits_sink)
);

endmodule
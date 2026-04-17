module Agent(
  input         clock,
  input         reset,
  input         io_inputAValid,
  input         io_inputDReady,
  input  [2:0]  io_inputRandomOpcode,
  input  [15:0] io_inputRandomAddress,
  input         io_a_ready,
  output        io_a_valid,
  output [2:0]  io_a_bits_opcode,
  output [2:0]  io_a_bits_param,
  output [2:0]  io_a_bits_size,
  output [4:0]  io_a_bits_source,
  output [15:0] io_a_bits_address,
  output [1:0]  io_a_bits_user_alias,
  output        io_a_bits_mask,
  output [7:0]  io_a_bits_data,
  output        io_a_bits_corrupt,
  output        io_b_ready,
  input         io_b_valid,
  input  [2:0]  io_b_bits_opcode,
  input  [1:0]  io_b_bits_param,
  input  [2:0]  io_b_bits_size,
  input  [4:0]  io_b_bits_source,
  input  [15:0] io_b_bits_address,
  input         io_b_bits_mask,
  input  [7:0]  io_b_bits_data,
  input         io_b_bits_corrupt,
  input         io_c_ready,
  output        io_c_valid,
  output [2:0]  io_c_bits_opcode,
  output [2:0]  io_c_bits_param,
  output [2:0]  io_c_bits_size,
  output [4:0]  io_c_bits_source,
  output [15:0] io_c_bits_address,
  output [1:0]  io_c_bits_user_alias,
  output [7:0]  io_c_bits_data,
  output        io_c_bits_corrupt,
  output        io_d_ready,
  input         io_d_valid,
  input  [2:0]  io_d_bits_opcode,
  input  [1:0]  io_d_bits_param,
  input  [2:0]  io_d_bits_size,
  input  [4:0]  io_d_bits_source,
  input  [7:0]  io_d_bits_sink,
  input         io_d_bits_denied,
  input  [7:0]  io_d_bits_data,
  input         io_d_bits_corrupt,
  input         io_e_ready,
  output        io_e_valid,
  output [7:0]  io_e_bits_sink
);
`ifdef RANDOMIZE_REG_INIT
  reg [31:0] _RAND_0;
  reg [31:0] _RAND_1;
  reg [31:0] _RAND_2;
  reg [31:0] _RAND_3;
`endif // RANDOMIZE_REG_INIT
  reg [7:0] regAOpcode; // @[Agent.scala 60:27]
  reg [7:0] randomAddr; // @[Agent.scala 118:27]
  wire [15:0] _randomAddr_T = io_inputAValid ? io_inputRandomAddress : {{8'd0}, randomAddr}; // @[Agent.scala 119:20]
  reg  aValid; // @[Agent.scala 123:23]
  wire  _aValid_T = io_a_ready ? 1'h0 : aValid; // @[Agent.scala 124:44]
  reg  aValidCnt; // @[Agent.scala 126:26]
  wire  _aValidCnt_T = aValid ? 1'h0 : aValidCnt; // @[Agent.scala 127:19]
  wire  _T_1 = io_inputRandomOpcode == 3'h7; // @[Agent.scala 140:35]
  wire [15:0] _GEN_3 = reset ? 16'h1 : _randomAddr_T; // @[Agent.scala 118:{27,27} 119:14]
  assign io_a_valid = aValidCnt & aValid; // @[Agent.scala 128:20]
  assign io_a_bits_opcode = regAOpcode[2:0]; // @[Agent.scala 149:20]
  assign io_a_bits_param = 3'h0; // @[Agent.scala 86:19]
  assign io_a_bits_size = 3'h1; // @[Agent.scala 87:19]
  assign io_a_bits_source = 5'h0; // @[Agent.scala 91:21]
  assign io_a_bits_address = {{8'd0}, randomAddr}; // @[Agent.scala 135:21]
  assign io_a_bits_user_alias = 2'h0; // @[Agent.scala 93:24]
  assign io_a_bits_mask = 1'h1; // @[Agent.scala 92:20]
  assign io_a_bits_data = 8'h2; // @[Agent.scala 88:19]
  assign io_a_bits_corrupt = 1'h0; // @[Agent.scala 89:21]
  assign io_b_ready = 1'h1; // @[Agent.scala 95:14]
  assign io_c_valid = 1'h1; // @[Agent.scala 97:14]
  assign io_c_bits_opcode = 3'h5; // @[Agent.scala 98:20]
  assign io_c_bits_param = 3'h0; // @[Agent.scala 99:21]
  assign io_c_bits_size = 3'h1; // @[Agent.scala 100:19]
  assign io_c_bits_source = 5'h0; // @[Agent.scala 101:21]
  assign io_c_bits_address = 16'h0; // @[Agent.scala 102:21]
  assign io_c_bits_user_alias = 2'h0; // @[Agent.scala 103:24]
  assign io_c_bits_data = 8'h0; // @[Agent.scala 104:19]
  assign io_c_bits_corrupt = 1'h0; // @[Agent.scala 105:21]
  assign io_d_ready = 1'h0; // @[Agent.scala 133:14]
  assign io_e_valid = 1'h1; // @[Agent.scala 107:14]
  assign io_e_bits_sink = 8'h0; // @[Agent.scala 108:18]
  always @(posedge clock) begin
    if (reset) begin // @[Agent.scala 60:27]
      regAOpcode <= 8'h6; // @[Agent.scala 60:27]
    end else if (io_inputRandomOpcode == 3'h6) begin // @[Agent.scala 137:57]
      regAOpcode <= 8'h6; // @[Agent.scala 66:16]
    end else if (io_inputRandomOpcode == 3'h7) begin // @[Agent.scala 140:62]
      regAOpcode <= 8'h7; // @[Agent.scala 72:16]
    end else if (_T_1) begin // @[Agent.scala 143:62]
      regAOpcode <= 8'h7; // @[Agent.scala 78:16]
    end
    randomAddr <= _GEN_3[7:0]; // @[Agent.scala 118:{27,27} 119:14]
    if (reset) begin // @[Agent.scala 123:23]
      aValid <= 1'h0; // @[Agent.scala 123:23]
    end else begin
      aValid <= io_inputAValid | _aValid_T; // @[Agent.scala 124:10]
    end
    aValidCnt <= reset | _aValidCnt_T; // @[Agent.scala 126:{26,26} 127:13]
  end
// Register and memory initialization
`ifdef RANDOMIZE_GARBAGE_ASSIGN
`define RANDOMIZE
`endif
`ifdef RANDOMIZE_INVALID_ASSIGN
`define RANDOMIZE
`endif
`ifdef RANDOMIZE_REG_INIT
`define RANDOMIZE
`endif
`ifdef RANDOMIZE_MEM_INIT
`define RANDOMIZE
`endif
`ifndef RANDOM
`define RANDOM $random
`endif
`ifdef RANDOMIZE_MEM_INIT
  integer initvar;
`endif
`ifndef SYNTHESIS
`ifdef FIRRTL_BEFORE_INITIAL
`FIRRTL_BEFORE_INITIAL
`endif
initial begin
  `ifdef RANDOMIZE
    `ifdef INIT_RANDOM
      `INIT_RANDOM
    `endif
    `ifndef VERILATOR
      `ifdef RANDOMIZE_DELAY
        #`RANDOMIZE_DELAY begin end
      `else
        #0.002 begin end
      `endif
    `endif
`ifdef RANDOMIZE_REG_INIT
  _RAND_0 = {1{`RANDOM}};
  regAOpcode = _RAND_0[7:0];
  _RAND_1 = {1{`RANDOM}};
  randomAddr = _RAND_1[7:0];
  _RAND_2 = {1{`RANDOM}};
  aValid = _RAND_2[0:0];
  _RAND_3 = {1{`RANDOM}};
  aValidCnt = _RAND_3[0:0];
`endif // RANDOMIZE_REG_INIT
  `endif // RANDOMIZE
end // initial
`ifdef FIRRTL_AFTER_INITIAL
`FIRRTL_AFTER_INITIAL
`endif
`endif // SYNTHESIS
endmodule

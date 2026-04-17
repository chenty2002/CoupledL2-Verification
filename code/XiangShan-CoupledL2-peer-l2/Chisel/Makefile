init:
	git submodule update --init
	cd rocket-chip && git submodule update --init hardfloat cde

compile:
	mill -i CoupledL2.compile

verify-l2l3l2:
	mill -i CoupledL2.test.runMain coupledL2.VerifyTop_L2L3L2

auto-l2l3l2:
	mill -i CoupledL2.test.runMain coupledL2.AutoVerify_L2L3L2

clean:
	rm -rf ./build

bsp:
	mill -i mill.bsp.BSP/install

idea:
	mill -i mill.scalalib.GenIdea/idea

reformat:
	mill -i __.reformat

checkformat:
	mill -i __.checkFormat

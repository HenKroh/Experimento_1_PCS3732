	.file	"4_bit_calc.cpp"
	.option pic
	.attribute arch, "rv64i2p1_m2p0_a2p1_f2p2_d2p2_c2p0_zicsr2p0_zifencei2p0_zmmul1p0_zaamo1p0_zalrsc1p0_zca1p0_zcd1p0"
	.attribute unaligned_access, 0
	.attribute stack_align, 16
# GNU C++17 (GCC) version 15.1.0 (riscv64-linux-gnu)
#	compiled by GNU C version 15.1.1 20250425, GMP version 6.3.0, MPFR version 4.2.2, MPC version 1.3.1, isl version isl-0.27-GMP

# warning: MPC header version 1.3.1 differs from library version 1.4.1.
# GGC heuristics: --param ggc-min-expand=100 --param ggc-min-heapsize=131072
# options passed: -mabi=lp64d -misa-spec=20191213 -mtls-dialect=trad -march=rv64imafdc_zicsr_zifencei_zmmul_zaamo_zalrsc_zca_zcd -O0 -std=c++17 -fno-asynchronous-unwind-tables
	.text
	.align	1
	.globl	_Z15handle_additionaaPb
	.type	_Z15handle_additionaaPb, @function
_Z15handle_additionaaPb:
.LFB15:
	.cfi_startproc
	addi	sp,sp,-48	#,,
	.cfi_def_cfa_offset 48
	sd	ra,40(sp)	#,
	sd	s0,32(sp)	#,
	.cfi_offset 1, -8
	.cfi_offset 8, -16
	addi	s0,sp,48	#,,
	.cfi_def_cfa 8, 0
	mv	a5,a0	# tmp150, A
	mv	a4,a1	# tmp152, B
	sd	a2,-48(s0)	# overflow, overflow
	sb	a5,-33(s0)	# tmp151, A
	mv	a5,a4	# tmp153, tmp152
	sb	a5,-34(s0)	# tmp153, B
# 4_bit_calc.cpp:16:   int8_t result = A + B;
	lbu	a4,-33(s0)	# A.0_1, A
	lbu	a5,-34(s0)	# B.1_2, B
	addw	a5,a4,a5	# B.1_2, tmp155, A.0_1
	andi	a5,a5,0xff	# _3, tmp154
# 4_bit_calc.cpp:16:   int8_t result = A + B;
	sb	a5,-17(s0)	# _3, result
# 4_bit_calc.cpp:17:   *overflow = (((A & 0x08) == (B & 0x08)) && ((result & 0x08) != (A & 0x08)));
	lbu	a4,-33(s0)	# A.3_4, A
# 4_bit_calc.cpp:17:   *overflow = (((A & 0x08) == (B & 0x08)) && ((result & 0x08) != (A & 0x08)));
	lbu	a5,-34(s0)	# B.4_5, B
# 4_bit_calc.cpp:17:   *overflow = (((A & 0x08) == (B & 0x08)) && ((result & 0x08) != (A & 0x08)));
	xor	a5,a4,a5	# B.4_5, tmp156, A.3_4
	andi	a5,a5,0xff	# _6, tmp156
	sext.w	a5,a5	# _7, _6
	andi	a5,a5,8	#, tmp157, _7
	sext.w	a5,a5	# _8, tmp157
# 4_bit_calc.cpp:17:   *overflow = (((A & 0x08) == (B & 0x08)) && ((result & 0x08) != (A & 0x08)));
	bne	a5,zero,.L2	#, _8,,
# 4_bit_calc.cpp:17:   *overflow = (((A & 0x08) == (B & 0x08)) && ((result & 0x08) != (A & 0x08)));
	lbu	a4,-17(s0)	# result.5_9, result
# 4_bit_calc.cpp:17:   *overflow = (((A & 0x08) == (B & 0x08)) && ((result & 0x08) != (A & 0x08)));
	lbu	a5,-33(s0)	# A.6_10, A
# 4_bit_calc.cpp:17:   *overflow = (((A & 0x08) == (B & 0x08)) && ((result & 0x08) != (A & 0x08)));
	xor	a5,a4,a5	# A.6_10, tmp158, result.5_9
	andi	a5,a5,0xff	# _11, tmp158
	sext.w	a5,a5	# _12, _11
	andi	a5,a5,8	#, tmp159, _12
	sext.w	a5,a5	# _13, tmp159
# 4_bit_calc.cpp:17:   *overflow = (((A & 0x08) == (B & 0x08)) && ((result & 0x08) != (A & 0x08)));
	beq	a5,zero,.L2	#, _13,,
# 4_bit_calc.cpp:17:   *overflow = (((A & 0x08) == (B & 0x08)) && ((result & 0x08) != (A & 0x08)));
	li	a5,1		# iftmp.2_14,
# 4_bit_calc.cpp:17:   *overflow = (((A & 0x08) == (B & 0x08)) && ((result & 0x08) != (A & 0x08)));
	j	.L3		#
.L2:
# 4_bit_calc.cpp:17:   *overflow = (((A & 0x08) == (B & 0x08)) && ((result & 0x08) != (A & 0x08)));
	li	a5,0		# iftmp.2_14,
.L3:
# 4_bit_calc.cpp:17:   *overflow = (((A & 0x08) == (B & 0x08)) && ((result & 0x08) != (A & 0x08)));
	ld	a4,-48(s0)		# tmp160, overflow
	sb	a5,0(a4)	# iftmp.2_14, *overflow_21(D)
# 4_bit_calc.cpp:18:   return result;
	lb	a5,-17(s0)		# _23, result
# 4_bit_calc.cpp:19: }
	mv	a0,a5	#, <retval>
	ld	ra,40(sp)		#,
	.cfi_restore 1
	ld	s0,32(sp)		#,
	.cfi_restore 8
	.cfi_def_cfa 2, 48
	addi	sp,sp,48	#,,
	.cfi_def_cfa_offset 0
	jr	ra		#
	.cfi_endproc
.LFE15:
	.size	_Z15handle_additionaaPb, .-_Z15handle_additionaaPb
	.align	1
	.globl	_Z21handle_multiplicationaaPb
	.type	_Z21handle_multiplicationaaPb, @function
_Z21handle_multiplicationaaPb:
.LFB16:
	.cfi_startproc
	addi	sp,sp,-48	#,,
	.cfi_def_cfa_offset 48
	sd	ra,40(sp)	#,
	sd	s0,32(sp)	#,
	.cfi_offset 1, -8
	.cfi_offset 8, -16
	addi	s0,sp,48	#,,
	.cfi_def_cfa 8, 0
	mv	a5,a0	# tmp149, A
	mv	a4,a1	# tmp151, B
	sd	a2,-48(s0)	# overflow, overflow
	sb	a5,-33(s0)	# tmp150, A
	mv	a5,a4	# tmp152, tmp151
	sb	a5,-34(s0)	# tmp152, B
# 4_bit_calc.cpp:22:   int8_t result = 0;
	sb	zero,-17(s0)	#, result
# 4_bit_calc.cpp:23:   if (B & 0x08) {
	lbu	a5,-34(s0)	# B.7_1, B
	sext.w	a5,a5	# _2, B.7_1
	andi	a5,a5,8	#, tmp153, _2
	sext.w	a5,a5	# _3, tmp153
# 4_bit_calc.cpp:23:   if (B & 0x08) {
	beq	a5,zero,.L6	#, _3,,
# 4_bit_calc.cpp:24:     B = ((B ^ 0x0F) + 1) & 0x0F;
	lbu	a5,-34(s0)	# tmp155, B
	xori	a5,a5,15	#, tmp156, tmp154
	slliw	a5,a5,24	#, _4, tmp156
	sraiw	a5,a5,24	#, _4, _4
	andi	a5,a5,0xff	# _5, _4
# 4_bit_calc.cpp:24:     B = ((B ^ 0x0F) + 1) & 0x0F;
	addiw	a5,a5,1	#, tmp158, _5
	andi	a5,a5,0xff	# _6, tmp157
	slliw	a5,a5,24	#, _7, _6
	sraiw	a5,a5,24	#, _7, _7
# 4_bit_calc.cpp:24:     B = ((B ^ 0x0F) + 1) & 0x0F;
	andi	a5,a5,15	#, tmp159, _7
	sb	a5,-34(s0)	# tmp159, B
# 4_bit_calc.cpp:25:     A = ((A ^ 0x0F) + 1) & 0x0F;
	lbu	a5,-33(s0)	# tmp161, A
	xori	a5,a5,15	#, tmp162, tmp160
	slliw	a5,a5,24	#, _8, tmp162
	sraiw	a5,a5,24	#, _8, _8
	andi	a5,a5,0xff	# _9, _8
# 4_bit_calc.cpp:25:     A = ((A ^ 0x0F) + 1) & 0x0F;
	addiw	a5,a5,1	#, tmp164, _9
	andi	a5,a5,0xff	# _10, tmp163
	slliw	a5,a5,24	#, _11, _10
	sraiw	a5,a5,24	#, _11, _11
# 4_bit_calc.cpp:25:     A = ((A ^ 0x0F) + 1) & 0x0F;
	andi	a5,a5,15	#, tmp165, _11
	sb	a5,-33(s0)	# tmp165, A
.L6:
# 4_bit_calc.cpp:27:   for (int i = 0; i < B; ++i) {
	sw	zero,-24(s0)	#, i
# 4_bit_calc.cpp:27:   for (int i = 0; i < B; ++i) {
	j	.L7		#
.L10:
# 4_bit_calc.cpp:28:     result = handle_addition(result, A, overflow);
	lb	a4,-33(s0)		# tmp166, A
	lb	a5,-17(s0)		# tmp167, result
	ld	a2,-48(s0)		#, overflow
	mv	a1,a4	#, tmp166
	mv	a0,a5	#, tmp167
	call	_Z15handle_additionaaPb		#
	mv	a5,a0	# tmp168,
	sb	a5,-17(s0)	# tmp168, result
# 4_bit_calc.cpp:29:     if (*overflow) break;
	ld	a5,-48(s0)		# tmp169, overflow
	lbu	a5,0(a5)	# _12, *overflow_28(D)
# 4_bit_calc.cpp:29:     if (*overflow) break;
	bne	a5,zero,.L12	#, _12,,
# 4_bit_calc.cpp:27:   for (int i = 0; i < B; ++i) {
	lw	a5,-24(s0)		# tmp172, i
	addiw	a5,a5,1	#, tmp173, tmp171
	sw	a5,-24(s0)	# i_31, i
.L7:
# 4_bit_calc.cpp:27:   for (int i = 0; i < B; ++i) {
	lb	a5,-34(s0)		# _13, B
	sext.w	a5,a5	# _13, _13
# 4_bit_calc.cpp:27:   for (int i = 0; i < B; ++i) {
	lw	a4,-24(s0)		# tmp176, i
	sext.w	a4,a4	# tmp177, tmp175
	blt	a4,a5,.L10	#, tmp177, _13,
	j	.L9		#
.L12:
# 4_bit_calc.cpp:29:     if (*overflow) break;
	nop	
.L9:
# 4_bit_calc.cpp:31:   return result;
	lb	a5,-17(s0)		# _32, result
# 4_bit_calc.cpp:32: }
	mv	a0,a5	#, <retval>
	ld	ra,40(sp)		#,
	.cfi_restore 1
	ld	s0,32(sp)		#,
	.cfi_restore 8
	.cfi_def_cfa 2, 48
	addi	sp,sp,48	#,,
	.cfi_def_cfa_offset 0
	jr	ra		#
	.cfi_endproc
.LFE16:
	.size	_Z21handle_multiplicationaaPb, .-_Z21handle_multiplicationaaPb
	.align	1
	.globl	_Z16handle_factorialaPb
	.type	_Z16handle_factorialaPb, @function
_Z16handle_factorialaPb:
.LFB17:
	.cfi_startproc
	addi	sp,sp,-48	#,,
	.cfi_def_cfa_offset 48
	sd	ra,40(sp)	#,
	sd	s0,32(sp)	#,
	.cfi_offset 1, -8
	.cfi_offset 8, -16
	addi	s0,sp,48	#,,
	.cfi_def_cfa 8, 0
	mv	a5,a0	# tmp142, A
	sd	a1,-48(s0)	# overflow, overflow
	sb	a5,-33(s0)	# tmp143, A
# 4_bit_calc.cpp:35:   int8_t result = 0;
	sb	zero,-17(s0)	#, result
# 4_bit_calc.cpp:36:   if (((A & 0x08) == 0x08) || (A == 0)) {
	lbu	a5,-33(s0)	# A.8_1, A
	sext.w	a5,a5	# _2, A.8_1
	andi	a5,a5,8	#, tmp144, _2
	sext.w	a5,a5	# _3, tmp144
# 4_bit_calc.cpp:36:   if (((A & 0x08) == 0x08) || (A == 0)) {
	bne	a5,zero,.L14	#, _3,,
# 4_bit_calc.cpp:36:   if (((A & 0x08) == 0x08) || (A == 0)) {
	lbu	a5,-33(s0)	# tmp146, A
	andi	a5,a5,0xff	# tmp147, tmp145
	bne	a5,zero,.L15	#, tmp147,,
.L14:
# 4_bit_calc.cpp:37:     *overflow = false;
	ld	a5,-48(s0)		# tmp148, overflow
	sb	zero,0(a5)	#, *overflow_19(D)
# 4_bit_calc.cpp:38:     return 0;
	li	a5,0		# _10,
	j	.L16		#
.L15:
# 4_bit_calc.cpp:40:   result = 1;
	li	a5,1		# tmp149,
	sb	a5,-17(s0)	# tmp149, result
# 4_bit_calc.cpp:41:   for (int i = 2; i <= A; ++i) {
	li	a5,2		# tmp150,
	sw	a5,-24(s0)	# tmp150, i
# 4_bit_calc.cpp:41:   for (int i = 2; i <= A; ++i) {
	j	.L17		#
.L20:
# 4_bit_calc.cpp:42:     result = handle_multiplication(result, i, overflow);
	lw	a5,-24(s0)		# tmp152, i
	slliw	a4,a5,24	#, _4, tmp151
	sraiw	a4,a4,24	#, _4, _4
	lb	a5,-17(s0)		# tmp153, result
	ld	a2,-48(s0)		#, overflow
	mv	a1,a4	#, _4
	mv	a0,a5	#, tmp153
	call	_Z21handle_multiplicationaaPb		#
	mv	a5,a0	# tmp154,
	sb	a5,-17(s0)	# tmp154, result
# 4_bit_calc.cpp:43:     if (*overflow) break;
	ld	a5,-48(s0)		# tmp155, overflow
	lbu	a5,0(a5)	# _5, *overflow_19(D)
# 4_bit_calc.cpp:43:     if (*overflow) break;
	bne	a5,zero,.L21	#, _5,,
# 4_bit_calc.cpp:41:   for (int i = 2; i <= A; ++i) {
	lw	a5,-24(s0)		# tmp158, i
	addiw	a5,a5,1	#, tmp159, tmp157
	sw	a5,-24(s0)	# i_22, i
.L17:
# 4_bit_calc.cpp:41:   for (int i = 2; i <= A; ++i) {
	lb	a5,-33(s0)		# _6, A
	sext.w	a5,a5	# _6, _6
# 4_bit_calc.cpp:41:   for (int i = 2; i <= A; ++i) {
	lw	a4,-24(s0)		# tmp162, i
	sext.w	a4,a4	# tmp163, tmp161
	ble	a4,a5,.L20	#, tmp163, _6,
	j	.L19		#
.L21:
# 4_bit_calc.cpp:43:     if (*overflow) break;
	nop	
.L19:
# 4_bit_calc.cpp:45:   return result;
	lb	a5,-17(s0)		# _10, result
.L16:
# 4_bit_calc.cpp:46: }
	mv	a0,a5	#, <retval>
	ld	ra,40(sp)		#,
	.cfi_restore 1
	ld	s0,32(sp)		#,
	.cfi_restore 8
	.cfi_def_cfa 2, 48
	addi	sp,sp,48	#,,
	.cfi_def_cfa_offset 0
	jr	ra		#
	.cfi_endproc
.LFE17:
	.size	_Z16handle_factorialaPb, .-_Z16handle_factorialaPb
	.section	.rodata
	.align	3
.LC0:
	.string	"add"
	.align	3
.LC1:
	.string	"sub"
	.align	3
.LC2:
	.string	"mul"
	.text
	.align	1
	.globl	_Z16handle_operationPKcaaPb
	.type	_Z16handle_operationPKcaaPb, @function
_Z16handle_operationPKcaaPb:
.LFB18:
	.cfi_startproc
	addi	sp,sp,-64	#,,
	.cfi_def_cfa_offset 64
	sd	ra,56(sp)	#,
	sd	s0,48(sp)	#,
	.cfi_offset 1, -8
	.cfi_offset 8, -16
	addi	s0,sp,64	#,,
	.cfi_def_cfa 8, 0
	sd	a0,-40(s0)	# op, op
	mv	a5,a1	# tmp143, A
	mv	a4,a2	# tmp145, B
	sd	a3,-56(s0)	# overflow, overflow
	sb	a5,-41(s0)	# tmp144, A
	mv	a5,a4	# tmp146, tmp145
	sb	a5,-42(s0)	# tmp146, B
# 4_bit_calc.cpp:50:   if (strcmp(op, "add") == 0) {
	lla	a1,.LC0	#,
	ld	a0,-40(s0)		#, op
	call	strcmp@plt	#
	mv	a5,a0	# tmp147,
# 4_bit_calc.cpp:50:   if (strcmp(op, "add") == 0) {
	bne	a5,zero,.L23	#, _1,,
# 4_bit_calc.cpp:51:     result = handle_addition(A, B, overflow);
	lb	a4,-42(s0)		# tmp148, B
	lb	a5,-41(s0)		# tmp149, A
	ld	a2,-56(s0)		#, overflow
	mv	a1,a4	#, tmp148
	mv	a0,a5	#, tmp149
	call	_Z15handle_additionaaPb		#
	mv	a5,a0	# tmp150,
	sb	a5,-17(s0)	# tmp150, result
	j	.L24		#
.L23:
# 4_bit_calc.cpp:52:   } else if (strcmp(op, "sub") == 0) {
	lla	a1,.LC1	#,
	ld	a0,-40(s0)		#, op
	call	strcmp@plt	#
	mv	a5,a0	# tmp151,
# 4_bit_calc.cpp:52:   } else if (strcmp(op, "sub") == 0) {
	bne	a5,zero,.L25	#, _2,,
# 4_bit_calc.cpp:53:     B = ((B ^ 0x0F) + 1) & 0x0F;
	lbu	a5,-42(s0)	# tmp153, B
	xori	a5,a5,15	#, tmp154, tmp152
	slliw	a5,a5,24	#, _3, tmp154
	sraiw	a5,a5,24	#, _3, _3
	andi	a5,a5,0xff	# _4, _3
# 4_bit_calc.cpp:53:     B = ((B ^ 0x0F) + 1) & 0x0F;
	addiw	a5,a5,1	#, tmp156, _4
	andi	a5,a5,0xff	# _5, tmp155
	slliw	a5,a5,24	#, _6, _5
	sraiw	a5,a5,24	#, _6, _6
# 4_bit_calc.cpp:53:     B = ((B ^ 0x0F) + 1) & 0x0F;
	andi	a5,a5,15	#, tmp157, _6
	sb	a5,-42(s0)	# tmp157, B
# 4_bit_calc.cpp:54:     result = handle_addition(A, B, overflow);
	lb	a4,-42(s0)		# tmp158, B
	lb	a5,-41(s0)		# tmp159, A
	ld	a2,-56(s0)		#, overflow
	mv	a1,a4	#, tmp158
	mv	a0,a5	#, tmp159
	call	_Z15handle_additionaaPb		#
	mv	a5,a0	# tmp160,
	sb	a5,-17(s0)	# tmp160, result
	j	.L24		#
.L25:
# 4_bit_calc.cpp:55:   } else if (strcmp(op, "mul") == 0) {
	lla	a1,.LC2	#,
	ld	a0,-40(s0)		#, op
	call	strcmp@plt	#
	mv	a5,a0	# tmp161,
# 4_bit_calc.cpp:55:   } else if (strcmp(op, "mul") == 0) {
	bne	a5,zero,.L26	#, _7,,
# 4_bit_calc.cpp:56:     result = handle_multiplication(A, B, overflow);
	lb	a4,-42(s0)		# tmp162, B
	lb	a5,-41(s0)		# tmp163, A
	ld	a2,-56(s0)		#, overflow
	mv	a1,a4	#, tmp162
	mv	a0,a5	#, tmp163
	call	_Z21handle_multiplicationaaPb		#
	mv	a5,a0	# tmp164,
	sb	a5,-17(s0)	# tmp164, result
	j	.L24		#
.L26:
# 4_bit_calc.cpp:58:     result = handle_factorial(A, overflow);
	lb	a5,-41(s0)		# tmp165, A
	ld	a1,-56(s0)		#, overflow
	mv	a0,a5	#, tmp165
	call	_Z16handle_factorialaPb		#
	mv	a5,a0	# tmp166,
	sb	a5,-17(s0)	# tmp166, result
.L24:
# 4_bit_calc.cpp:60:   return result & 0x0F;
	lbu	a5,-17(s0)	# tmp168, result
	andi	a5,a5,15	#, tmp169, tmp167
	slliw	a5,a5,24	#, _24, tmp169
	sraiw	a5,a5,24	#, _24, _24
# 4_bit_calc.cpp:61: }
	mv	a0,a5	#, <retval>
	ld	ra,56(sp)		#,
	.cfi_restore 1
	ld	s0,48(sp)		#,
	.cfi_restore 8
	.cfi_def_cfa 2, 64
	addi	sp,sp,64	#,,
	.cfi_def_cfa_offset 0
	jr	ra		#
	.cfi_endproc
.LFE18:
	.size	_Z16handle_operationPKcaaPb, .-_Z16handle_operationPKcaaPb
	.section	.rodata
	.align	3
.LC3:
	.string	"uso: %s <add|sub|mul|fac> <A em binario 4 bits> [B em binario 4 bits]\n"
	.align	3
.LC4:
	.string	"Resultado: "
	.align	3
.LC5:
	.string	"Overflow"
	.text
	.align	1
	.globl	main
	.type	main, @function
main:
.LFB19:
	.cfi_startproc
	addi	sp,sp,-64	#,,
	.cfi_def_cfa_offset 64
	sd	ra,56(sp)	#,
	sd	s0,48(sp)	#,
	.cfi_offset 1, -8
	.cfi_offset 8, -16
	addi	s0,sp,64	#,,
	.cfi_def_cfa 8, 0
	mv	a5,a0	# tmp149, argc
	sd	a1,-64(s0)	# argv, argv
	sw	a5,-52(s0)	# tmp150, argc
# 4_bit_calc.cpp:64:   if (argc < 3) {
	lw	a5,-52(s0)		# tmp152, argc
	sext.w	a4,a5	# tmp153, tmp151
	li	a5,2		# tmp154,
	bgt	a4,a5,.L29	#, tmp153, tmp154,
# 4_bit_calc.cpp:65:     fprintf(stderr, "uso: %s <add|sub|mul|fac> <A em binario 4 bits> [B em binario 4 bits]\n", argv[0]);
	la	a5,stderr		# tmp155,
	ld	a4,0(a5)		# stderr.9_1, stderr
	ld	a5,-64(s0)		# tmp156, argv
	ld	a5,0(a5)		# _2, *argv_24(D)
	mv	a2,a5	#, _2
	lla	a1,.LC3	#,
	mv	a0,a4	#, stderr.9_1
	call	fprintf@plt	#
# 4_bit_calc.cpp:66:     return 1;
	li	a5,1		# _15,
	j	.L38		#
.L29:
# 4_bit_calc.cpp:69:   const char* op = argv[1];
	ld	a5,-64(s0)		# tmp157, argv
	ld	a5,8(a5)		# tmp158, MEM[(char * *)argv_24(D) + 8B]
	sd	a5,-32(s0)	# tmp158, op
# 4_bit_calc.cpp:70:   int8_t A = (int8_t)strtol(argv[2], nullptr, 2);
	ld	a5,-64(s0)		# tmp159, argv
	addi	a5,a5,16	#, _3, tmp159
# 4_bit_calc.cpp:70:   int8_t A = (int8_t)strtol(argv[2], nullptr, 2);
	ld	a5,0(a5)		# _4, *_3
	li	a2,2		#,
	li	a1,0		#,
	mv	a0,a5	#, _4
	call	__isoc23_strtol@plt	#
	mv	a5,a0	# _5,
# 4_bit_calc.cpp:70:   int8_t A = (int8_t)strtol(argv[2], nullptr, 2);
	sb	a5,-33(s0)	# _5, A
# 4_bit_calc.cpp:71:   int8_t B = (argc > 3) ? (int8_t)strtol(argv[3], nullptr, 2) : 0;
	lw	a5,-52(s0)		# tmp161, argc
	sext.w	a4,a5	# tmp162, tmp160
	li	a5,3		# tmp163,
	ble	a4,a5,.L31	#, tmp162, tmp163,
# 4_bit_calc.cpp:71:   int8_t B = (argc > 3) ? (int8_t)strtol(argv[3], nullptr, 2) : 0;
	ld	a5,-64(s0)		# tmp164, argv
	addi	a5,a5,24	#, _6, tmp164
# 4_bit_calc.cpp:71:   int8_t B = (argc > 3) ? (int8_t)strtol(argv[3], nullptr, 2) : 0;
	ld	a5,0(a5)		# _7, *_6
	li	a2,2		#,
	li	a1,0		#,
	mv	a0,a5	#, _7
	call	__isoc23_strtol@plt	#
	mv	a5,a0	# _8,
# 4_bit_calc.cpp:71:   int8_t B = (argc > 3) ? (int8_t)strtol(argv[3], nullptr, 2) : 0;
	sb	a5,-17(s0)	# _8, B
	j	.L32		#
.L31:
# 4_bit_calc.cpp:71:   int8_t B = (argc > 3) ? (int8_t)strtol(argv[3], nullptr, 2) : 0;
	sb	zero,-17(s0)	#, B
.L32:
# 4_bit_calc.cpp:73:   bool overflow = false;
	sb	zero,-35(s0)	#, overflow
# 4_bit_calc.cpp:74:   int8_t result = handle_operation(op, A, B, &overflow);
	addi	a3,s0,-35	#, tmp165,
	lb	a4,-17(s0)		# tmp166, B
	lb	a5,-33(s0)		# tmp167, A
	mv	a2,a4	#, tmp166
	mv	a1,a5	#, tmp167
	ld	a0,-32(s0)		#, op
	call	_Z16handle_operationPKcaaPb		#
	mv	a5,a0	# tmp168,
	sb	a5,-34(s0)	# tmp168, result
# 4_bit_calc.cpp:76:   printf("Resultado: ");
	lla	a0,.LC4	#,
	call	printf@plt	#
# 4_bit_calc.cpp:77:   for (int bit = 3; bit >= 0; --bit) {
	li	a5,3		# tmp169,
	sw	a5,-24(s0)	# tmp169, bit
# 4_bit_calc.cpp:77:   for (int bit = 3; bit >= 0; --bit) {
	j	.L33		#
.L36:
# 4_bit_calc.cpp:78:     putchar(((result >> bit) & 1) ? '1' : '0');
	lb	a5,-34(s0)		# _9, result
	sext.w	a5,a5	# _9, _9
# 4_bit_calc.cpp:78:     putchar(((result >> bit) & 1) ? '1' : '0');
	lw	a4,-24(s0)		# tmp172, bit
	sraw	a5,a5,a4	# tmp171, tmp174, _9
	sext.w	a5,a5	# _10, _10
# 4_bit_calc.cpp:78:     putchar(((result >> bit) & 1) ? '1' : '0');
	andi	a5,a5,1	#, tmp175, _10
	sext.w	a5,a5	# _11, tmp175
# 4_bit_calc.cpp:78:     putchar(((result >> bit) & 1) ? '1' : '0');
	beq	a5,zero,.L34	#, _11,,
# 4_bit_calc.cpp:78:     putchar(((result >> bit) & 1) ? '1' : '0');
	li	a5,49		# iftmp.10_16,
	j	.L35		#
.L34:
# 4_bit_calc.cpp:78:     putchar(((result >> bit) & 1) ? '1' : '0');
	li	a5,48		# iftmp.10_16,
.L35:
# 4_bit_calc.cpp:78:     putchar(((result >> bit) & 1) ? '1' : '0');
	mv	a0,a5	#, iftmp.10_16
	call	putchar@plt	#
# 4_bit_calc.cpp:77:   for (int bit = 3; bit >= 0; --bit) {
	lw	a5,-24(s0)		# tmp178, bit
	addiw	a5,a5,-1	#, tmp179, tmp177
	sw	a5,-24(s0)	# bit_42, bit
.L33:
# 4_bit_calc.cpp:77:   for (int bit = 3; bit >= 0; --bit) {
	lw	a5,-24(s0)		# tmp181, bit
	sext.w	a5,a5	# tmp182, tmp180
	bge	a5,zero,.L36	#, tmp182,,
# 4_bit_calc.cpp:80:   putchar('\n');
	li	a0,10		#,
	call	putchar@plt	#
# 4_bit_calc.cpp:81:   if (overflow) {
	lbu	a5,-35(s0)	# overflow.11_12, overflow
	beq	a5,zero,.L37	#, overflow.11_12,,
# 4_bit_calc.cpp:82:     printf("Overflow\n");
	lla	a0,.LC5	#,
	call	puts@plt	#
.L37:
# 4_bit_calc.cpp:85:   return 0;
	li	a5,0		# _15,
.L38:
# 4_bit_calc.cpp:86: }
	mv	a0,a5	#, <retval>
	ld	ra,56(sp)		#,
	.cfi_restore 1
	ld	s0,48(sp)		#,
	.cfi_restore 8
	.cfi_def_cfa 2, 64
	addi	sp,sp,64	#,,
	.cfi_def_cfa_offset 0
	jr	ra		#
	.cfi_endproc
.LFE19:
	.size	main, .-main
	.ident	"GCC: (GNU) 15.1.0"
	.section	.note.GNU-stack,"",@progbits

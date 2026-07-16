	.arch armv8-a
	.file	"4_bit_calc.cpp"
// GNU C++17 (GCC) version 15.2.0 (aarch64-linux-gnu)
//	compiled by GNU C version 15.2.1 20260209, GMP version 6.3.0, MPFR version 4.2.2, MPC version 1.4.1, isl version isl-0.27-GMP

// GGC heuristics: --param ggc-min-expand=100 --param ggc-min-heapsize=131072
// options passed: -mlittle-endian -mabi=lp64 -O0 -std=c++17 -fno-asynchronous-unwind-tables
	.text
	.align	2
	.global	_Z15handle_additionaaPb
	.type	_Z15handle_additionaaPb, %function
_Z15handle_additionaaPb:
.LFB15:
	.cfi_startproc
	sub	sp, sp, #32	//,,
	.cfi_def_cfa_offset 32
	strb	w0, [sp, 15]	// A, A
	strb	w1, [sp, 14]	// B, B
	str	x2, [sp]	// overflow, overflow
// 4_bit_calc.cpp:16:   int8_t result = A + B;
	ldrb	w1, [sp, 15]	// A.0_1, A
	ldrb	w0, [sp, 14]	// B.1_2, B
	add	w0, w1, w0	// tmp117, A.0_1, B.1_2
	and	w0, w0, 255	// _3, tmp117
// 4_bit_calc.cpp:16:   int8_t result = A + B;
	strb	w0, [sp, 31]	// tmp118, result
// 4_bit_calc.cpp:17:   *overflow = (((A & 0x08) == (B & 0x08)) && ((result & 0x08) != (A & 0x08)));
	ldrb	w1, [sp, 15]	// A.3_4, A
// 4_bit_calc.cpp:17:   *overflow = (((A & 0x08) == (B & 0x08)) && ((result & 0x08) != (A & 0x08)));
	ldrb	w0, [sp, 14]	// B.4_5, B
// 4_bit_calc.cpp:17:   *overflow = (((A & 0x08) == (B & 0x08)) && ((result & 0x08) != (A & 0x08)));
	eor	w0, w1, w0	// tmp119, A.3_4, B.4_5
	and	w0, w0, 255	// _6, tmp119
	and	w0, w0, 8	// _8, _7,
// 4_bit_calc.cpp:17:   *overflow = (((A & 0x08) == (B & 0x08)) && ((result & 0x08) != (A & 0x08)));
	cmp	w0, 0	// _8,
	bne	.L2		//,
// 4_bit_calc.cpp:17:   *overflow = (((A & 0x08) == (B & 0x08)) && ((result & 0x08) != (A & 0x08)));
	ldrb	w1, [sp, 31]	// result.5_9, result
// 4_bit_calc.cpp:17:   *overflow = (((A & 0x08) == (B & 0x08)) && ((result & 0x08) != (A & 0x08)));
	ldrb	w0, [sp, 15]	// A.6_10, A
// 4_bit_calc.cpp:17:   *overflow = (((A & 0x08) == (B & 0x08)) && ((result & 0x08) != (A & 0x08)));
	eor	w0, w1, w0	// tmp120, result.5_9, A.6_10
	and	w0, w0, 255	// _11, tmp120
	and	w0, w0, 8	// _13, _12,
// 4_bit_calc.cpp:17:   *overflow = (((A & 0x08) == (B & 0x08)) && ((result & 0x08) != (A & 0x08)));
	cmp	w0, 0	// _13,
	beq	.L2		//,
// 4_bit_calc.cpp:17:   *overflow = (((A & 0x08) == (B & 0x08)) && ((result & 0x08) != (A & 0x08)));
	mov	w1, 1	// iftmp.2_14,
// 4_bit_calc.cpp:17:   *overflow = (((A & 0x08) == (B & 0x08)) && ((result & 0x08) != (A & 0x08)));
	b	.L3		//
.L2:
// 4_bit_calc.cpp:17:   *overflow = (((A & 0x08) == (B & 0x08)) && ((result & 0x08) != (A & 0x08)));
	mov	w1, 0	// iftmp.2_14,
.L3:
// 4_bit_calc.cpp:17:   *overflow = (((A & 0x08) == (B & 0x08)) && ((result & 0x08) != (A & 0x08)));
	ldr	x0, [sp]	// tmp121, overflow
	strb	w1, [x0]	// tmp122, *overflow_21(D)
// 4_bit_calc.cpp:18:   return result;
	ldrsb	w0, [sp, 31]	// _23, result
// 4_bit_calc.cpp:19: }
	add	sp, sp, 32	//,,
	.cfi_def_cfa_offset 0
	ret	
	.cfi_endproc
.LFE15:
	.size	_Z15handle_additionaaPb, .-_Z15handle_additionaaPb
	.align	2
	.global	_Z21handle_multiplicationaaPb
	.type	_Z21handle_multiplicationaaPb, %function
_Z21handle_multiplicationaaPb:
.LFB16:
	.cfi_startproc
	stp	x29, x30, [sp, -48]!	//,,,
	.cfi_def_cfa_offset 48
	.cfi_offset 29, -48
	.cfi_offset 30, -40
	mov	x29, sp	//,
	strb	w0, [sp, 31]	// A, A
	strb	w1, [sp, 30]	// B, B
	str	x2, [sp, 16]	// overflow, overflow
// 4_bit_calc.cpp:22:   int8_t result = 0;
	strb	wzr, [sp, 47]	//, result
// 4_bit_calc.cpp:23:   if (B & 0x08) {
	ldrb	w0, [sp, 30]	// B.7_1, B
	and	w0, w0, 8	// _3, _2,
// 4_bit_calc.cpp:23:   if (B & 0x08) {
	cmp	w0, 0	// _3,
	beq	.L6		//,
// 4_bit_calc.cpp:24:     B = ((B ^ 0x0F) + 1) & 0x0F;
	ldrb	w0, [sp, 30]	// tmp116, B
	eor	w0, w0, 15	// tmp117, tmp116,
	sxtb	w0, w0	// _4, tmp117
	and	w0, w0, 255	// _5, _4
// 4_bit_calc.cpp:24:     B = ((B ^ 0x0F) + 1) & 0x0F;
	add	w0, w0, 1	// tmp118, _5,
	and	w0, w0, 255	// _6, tmp118
	sxtb	w0, w0	// _7, _6
// 4_bit_calc.cpp:24:     B = ((B ^ 0x0F) + 1) & 0x0F;
	and	w0, w0, 15	// tmp119, _7,
	strb	w0, [sp, 30]	// tmp120, B
// 4_bit_calc.cpp:25:     A = ((A ^ 0x0F) + 1) & 0x0F;
	ldrb	w0, [sp, 31]	// tmp121, A
	eor	w0, w0, 15	// tmp122, tmp121,
	sxtb	w0, w0	// _8, tmp122
	and	w0, w0, 255	// _9, _8
// 4_bit_calc.cpp:25:     A = ((A ^ 0x0F) + 1) & 0x0F;
	add	w0, w0, 1	// tmp123, _9,
	and	w0, w0, 255	// _10, tmp123
	sxtb	w0, w0	// _11, _10
// 4_bit_calc.cpp:25:     A = ((A ^ 0x0F) + 1) & 0x0F;
	and	w0, w0, 15	// tmp124, _11,
	strb	w0, [sp, 31]	// tmp125, A
.L6:
// 4_bit_calc.cpp:27:   for (int i = 0; i < B; ++i) {
	str	wzr, [sp, 40]	//, i
// 4_bit_calc.cpp:27:   for (int i = 0; i < B; ++i) {
	b	.L7		//
.L10:
// 4_bit_calc.cpp:28:     result = handle_addition(result, A, overflow);
	ldr	x2, [sp, 16]	//, overflow
	ldrb	w1, [sp, 31]	//, A
	ldrb	w0, [sp, 47]	//, result
	bl	_Z15handle_additionaaPb		//
	strb	w0, [sp, 47]	//, result
// 4_bit_calc.cpp:29:     if (*overflow) break;
	ldr	x0, [sp, 16]	// tmp126, overflow
	ldrb	w0, [x0]	// _12, *overflow_28(D)
// 4_bit_calc.cpp:29:     if (*overflow) break;
	and	w0, w0, 1	// tmp127, _12,
	cmp	w0, 0	// tmp127,
	bne	.L12		//,
// 4_bit_calc.cpp:27:   for (int i = 0; i < B; ++i) {
	ldr	w0, [sp, 40]	// tmp129, i
	add	w0, w0, 1	// i_31, tmp129,
	str	w0, [sp, 40]	// i_31, i
.L7:
// 4_bit_calc.cpp:27:   for (int i = 0; i < B; ++i) {
	ldrsb	w0, [sp, 30]	// _13, B
// 4_bit_calc.cpp:27:   for (int i = 0; i < B; ++i) {
	ldr	w1, [sp, 40]	// tmp130, i
	cmp	w1, w0	// tmp130, _13
	blt	.L10		//,
	b	.L9		//
.L12:
// 4_bit_calc.cpp:29:     if (*overflow) break;
	nop	
.L9:
// 4_bit_calc.cpp:31:   return result;
	ldrsb	w0, [sp, 47]	// _32, result
// 4_bit_calc.cpp:32: }
	ldp	x29, x30, [sp], 48	//,,,
	.cfi_restore 30
	.cfi_restore 29
	.cfi_def_cfa_offset 0
	ret	
	.cfi_endproc
.LFE16:
	.size	_Z21handle_multiplicationaaPb, .-_Z21handle_multiplicationaaPb
	.align	2
	.global	_Z16handle_factorialaPb
	.type	_Z16handle_factorialaPb, %function
_Z16handle_factorialaPb:
.LFB17:
	.cfi_startproc
	stp	x29, x30, [sp, -48]!	//,,,
	.cfi_def_cfa_offset 48
	.cfi_offset 29, -48
	.cfi_offset 30, -40
	mov	x29, sp	//,
	strb	w0, [sp, 31]	// A, A
	str	x1, [sp, 16]	// overflow, overflow
// 4_bit_calc.cpp:35:   int8_t result = 0;
	strb	wzr, [sp, 47]	//, result
// 4_bit_calc.cpp:36:   if (((A & 0x08) == 0x08) || (A == 0)) {
	ldrb	w0, [sp, 31]	// A.8_1, A
	and	w0, w0, 8	// _3, _2,
// 4_bit_calc.cpp:36:   if (((A & 0x08) == 0x08) || (A == 0)) {
	cmp	w0, 0	// _3,
	bne	.L14		//,
// 4_bit_calc.cpp:36:   if (((A & 0x08) == 0x08) || (A == 0)) {
	ldrsb	w0, [sp, 31]	// tmp109, A
	cmp	w0, 0	// tmp109,
	bne	.L15		//,
.L14:
// 4_bit_calc.cpp:37:     *overflow = false;
	ldr	x0, [sp, 16]	// tmp110, overflow
	strb	wzr, [x0]	//, *overflow_19(D)
// 4_bit_calc.cpp:38:     return 0;
	mov	w0, 0	// _10,
	b	.L16		//
.L15:
// 4_bit_calc.cpp:40:   result = 1;
	mov	w0, 1	// tmp111,
	strb	w0, [sp, 47]	// tmp111, result
// 4_bit_calc.cpp:41:   for (int i = 2; i <= A; ++i) {
	mov	w0, 2	// tmp112,
	str	w0, [sp, 40]	// tmp112, i
// 4_bit_calc.cpp:41:   for (int i = 2; i <= A; ++i) {
	b	.L17		//
.L20:
// 4_bit_calc.cpp:42:     result = handle_multiplication(result, i, overflow);
	ldr	w0, [sp, 40]	// tmp113, i
	sxtb	w0, w0	// _4, tmp113
	ldr	x2, [sp, 16]	//, overflow
	mov	w1, w0	//, _4
	ldrb	w0, [sp, 47]	//, result
	bl	_Z21handle_multiplicationaaPb		//
	strb	w0, [sp, 47]	//, result
// 4_bit_calc.cpp:43:     if (*overflow) break;
	ldr	x0, [sp, 16]	// tmp114, overflow
	ldrb	w0, [x0]	// _5, *overflow_19(D)
// 4_bit_calc.cpp:43:     if (*overflow) break;
	and	w0, w0, 1	// tmp115, _5,
	cmp	w0, 0	// tmp115,
	bne	.L21		//,
// 4_bit_calc.cpp:41:   for (int i = 2; i <= A; ++i) {
	ldr	w0, [sp, 40]	// tmp117, i
	add	w0, w0, 1	// i_22, tmp117,
	str	w0, [sp, 40]	// i_22, i
.L17:
// 4_bit_calc.cpp:41:   for (int i = 2; i <= A; ++i) {
	ldrsb	w0, [sp, 31]	// _6, A
// 4_bit_calc.cpp:41:   for (int i = 2; i <= A; ++i) {
	ldr	w1, [sp, 40]	// tmp118, i
	cmp	w1, w0	// tmp118, _6
	ble	.L20		//,
	b	.L19		//
.L21:
// 4_bit_calc.cpp:43:     if (*overflow) break;
	nop	
.L19:
// 4_bit_calc.cpp:45:   return result;
	ldrsb	w0, [sp, 47]	// _10, result
.L16:
// 4_bit_calc.cpp:46: }
	ldp	x29, x30, [sp], 48	//,,,
	.cfi_restore 30
	.cfi_restore 29
	.cfi_def_cfa_offset 0
	ret	
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
	.align	2
	.global	_Z16handle_operationPKcaaPb
	.type	_Z16handle_operationPKcaaPb, %function
_Z16handle_operationPKcaaPb:
.LFB18:
	.cfi_startproc
	stp	x29, x30, [sp, -64]!	//,,,
	.cfi_def_cfa_offset 64
	.cfi_offset 29, -64
	.cfi_offset 30, -56
	mov	x29, sp	//,
	str	x0, [sp, 40]	// op, op
	strb	w1, [sp, 39]	// A, A
	strb	w2, [sp, 38]	// B, B
	str	x3, [sp, 24]	// overflow, overflow
// 4_bit_calc.cpp:50:   if (strcmp(op, "add") == 0) {
	adrp	x0, .LC0	// tmp110,
	add	x1, x0, :lo12:.LC0	//, tmp110,
	ldr	x0, [sp, 40]	//, op
	bl	strcmp		//
// 4_bit_calc.cpp:50:   if (strcmp(op, "add") == 0) {
	cmp	w0, 0	// _1,
	bne	.L23		//,
// 4_bit_calc.cpp:51:     result = handle_addition(A, B, overflow);
	ldr	x2, [sp, 24]	//, overflow
	ldrb	w1, [sp, 38]	//, B
	ldrb	w0, [sp, 39]	//, A
	bl	_Z15handle_additionaaPb		//
	strb	w0, [sp, 63]	//, result
	b	.L24		//
.L23:
// 4_bit_calc.cpp:52:   } else if (strcmp(op, "sub") == 0) {
	adrp	x0, .LC1	// tmp111,
	add	x1, x0, :lo12:.LC1	//, tmp111,
	ldr	x0, [sp, 40]	//, op
	bl	strcmp		//
// 4_bit_calc.cpp:52:   } else if (strcmp(op, "sub") == 0) {
	cmp	w0, 0	// _2,
	bne	.L25		//,
// 4_bit_calc.cpp:53:     B = ((B ^ 0x0F) + 1) & 0x0F;
	ldrb	w0, [sp, 38]	// tmp112, B
	eor	w0, w0, 15	// tmp113, tmp112,
	sxtb	w0, w0	// _3, tmp113
	and	w0, w0, 255	// _4, _3
// 4_bit_calc.cpp:53:     B = ((B ^ 0x0F) + 1) & 0x0F;
	add	w0, w0, 1	// tmp114, _4,
	and	w0, w0, 255	// _5, tmp114
	sxtb	w0, w0	// _6, _5
// 4_bit_calc.cpp:53:     B = ((B ^ 0x0F) + 1) & 0x0F;
	and	w0, w0, 15	// tmp115, _6,
	strb	w0, [sp, 38]	// tmp116, B
// 4_bit_calc.cpp:54:     result = handle_addition(A, B, overflow);
	ldr	x2, [sp, 24]	//, overflow
	ldrb	w1, [sp, 38]	//, B
	ldrb	w0, [sp, 39]	//, A
	bl	_Z15handle_additionaaPb		//
	strb	w0, [sp, 63]	//, result
	b	.L24		//
.L25:
// 4_bit_calc.cpp:55:   } else if (strcmp(op, "mul") == 0) {
	adrp	x0, .LC2	// tmp117,
	add	x1, x0, :lo12:.LC2	//, tmp117,
	ldr	x0, [sp, 40]	//, op
	bl	strcmp		//
// 4_bit_calc.cpp:55:   } else if (strcmp(op, "mul") == 0) {
	cmp	w0, 0	// _7,
	bne	.L26		//,
// 4_bit_calc.cpp:56:     result = handle_multiplication(A, B, overflow);
	ldr	x2, [sp, 24]	//, overflow
	ldrb	w1, [sp, 38]	//, B
	ldrb	w0, [sp, 39]	//, A
	bl	_Z21handle_multiplicationaaPb		//
	strb	w0, [sp, 63]	//, result
	b	.L24		//
.L26:
// 4_bit_calc.cpp:58:     result = handle_factorial(A, overflow);
	ldr	x1, [sp, 24]	//, overflow
	ldrb	w0, [sp, 39]	//, A
	bl	_Z16handle_factorialaPb		//
	strb	w0, [sp, 63]	//, result
.L24:
// 4_bit_calc.cpp:60:   return result & 0x0F;
	ldrb	w0, [sp, 63]	// tmp118, result
	and	w0, w0, 15	// tmp119, tmp118,
	sxtb	w0, w0	// _24, tmp119
// 4_bit_calc.cpp:61: }
	ldp	x29, x30, [sp], 64	//,,,
	.cfi_restore 30
	.cfi_restore 29
	.cfi_def_cfa_offset 0
	ret	
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
	.align	2
	.global	main
	.type	main, %function
main:
.LFB19:
	.cfi_startproc
	stp	x29, x30, [sp, -64]!	//,,,
	.cfi_def_cfa_offset 64
	.cfi_offset 29, -64
	.cfi_offset 30, -56
	mov	x29, sp	//,
	str	w0, [sp, 28]	// argc, argc
	str	x1, [sp, 16]	// argv, argv
// 4_bit_calc.cpp:64:   if (argc < 3) {
	ldr	w0, [sp, 28]	// tmp116, argc
	cmp	w0, 2	// tmp116,
	bgt	.L29		//,
// 4_bit_calc.cpp:65:     fprintf(stderr, "uso: %s <add|sub|mul|fac> <A em binario 4 bits> [B em binario 4 bits]\n", argv[0]);
	adrp	x0, :got:stderr	// tmp117,
	ldr	x0, [x0, :got_lo12:stderr]	// tmp117,
	ldr	x3, [x0]	// stderr.9_1, stderr
	ldr	x0, [sp, 16]	// tmp118, argv
	ldr	x0, [x0]	// _2, *argv_24(D)
	mov	x2, x0	//, _2
	adrp	x0, .LC3	// tmp119,
	add	x1, x0, :lo12:.LC3	//, tmp119,
	mov	x0, x3	//, stderr.9_1
	bl	fprintf		//
// 4_bit_calc.cpp:66:     return 1;
	mov	w0, 1	// _15,
	b	.L38		//
.L29:
// 4_bit_calc.cpp:69:   const char* op = argv[1];
	ldr	x0, [sp, 16]	// tmp120, argv
	ldr	x0, [x0, 8]	// tmp121, MEM[(char * *)argv_24(D) + 8B]
	str	x0, [sp, 48]	// tmp121, op
// 4_bit_calc.cpp:70:   int8_t A = (int8_t)strtol(argv[2], nullptr, 2);
	ldr	x0, [sp, 16]	// tmp122, argv
	add	x0, x0, 16	// _3, tmp122,
// 4_bit_calc.cpp:70:   int8_t A = (int8_t)strtol(argv[2], nullptr, 2);
	ldr	x0, [x0]	// _4, *_3
	mov	w2, 2	//,
	mov	x1, 0	//,
	bl	__isoc23_strtol		//
// 4_bit_calc.cpp:70:   int8_t A = (int8_t)strtol(argv[2], nullptr, 2);
	strb	w0, [sp, 47]	// tmp123, A
// 4_bit_calc.cpp:71:   int8_t B = (argc > 3) ? (int8_t)strtol(argv[3], nullptr, 2) : 0;
	ldr	w0, [sp, 28]	// tmp124, argc
	cmp	w0, 3	// tmp124,
	ble	.L31		//,
// 4_bit_calc.cpp:71:   int8_t B = (argc > 3) ? (int8_t)strtol(argv[3], nullptr, 2) : 0;
	ldr	x0, [sp, 16]	// tmp125, argv
	add	x0, x0, 24	// _6, tmp125,
// 4_bit_calc.cpp:71:   int8_t B = (argc > 3) ? (int8_t)strtol(argv[3], nullptr, 2) : 0;
	ldr	x0, [x0]	// _7, *_6
	mov	w2, 2	//,
	mov	x1, 0	//,
	bl	__isoc23_strtol		//
// 4_bit_calc.cpp:71:   int8_t B = (argc > 3) ? (int8_t)strtol(argv[3], nullptr, 2) : 0;
	strb	w0, [sp, 63]	// tmp126, B
	b	.L32		//
.L31:
// 4_bit_calc.cpp:71:   int8_t B = (argc > 3) ? (int8_t)strtol(argv[3], nullptr, 2) : 0;
	strb	wzr, [sp, 63]	//, B
.L32:
// 4_bit_calc.cpp:73:   bool overflow = false;
	strb	wzr, [sp, 45]	//, overflow
// 4_bit_calc.cpp:74:   int8_t result = handle_operation(op, A, B, &overflow);
	add	x0, sp, 45	// tmp127,,
	mov	x3, x0	//, tmp127
	ldrb	w2, [sp, 63]	//, B
	ldrb	w1, [sp, 47]	//, A
	ldr	x0, [sp, 48]	//, op
	bl	_Z16handle_operationPKcaaPb		//
	strb	w0, [sp, 46]	//, result
// 4_bit_calc.cpp:76:   printf("Resultado: ");
	adrp	x0, .LC4	// tmp128,
	add	x0, x0, :lo12:.LC4	//, tmp128,
	bl	printf		//
// 4_bit_calc.cpp:77:   for (int bit = 3; bit >= 0; --bit) {
	mov	w0, 3	// tmp129,
	str	w0, [sp, 56]	// tmp129, bit
// 4_bit_calc.cpp:77:   for (int bit = 3; bit >= 0; --bit) {
	b	.L33		//
.L36:
// 4_bit_calc.cpp:78:     putchar(((result >> bit) & 1) ? '1' : '0');
	ldrsb	w1, [sp, 46]	// _9, result
// 4_bit_calc.cpp:78:     putchar(((result >> bit) & 1) ? '1' : '0');
	ldr	w0, [sp, 56]	// tmp130, bit
	asr	w0, w1, w0	// _10, _9, tmp130
// 4_bit_calc.cpp:78:     putchar(((result >> bit) & 1) ? '1' : '0');
	and	w0, w0, 1	// _11, _10,
// 4_bit_calc.cpp:78:     putchar(((result >> bit) & 1) ? '1' : '0');
	cmp	w0, 0	// _11,
	beq	.L34		//,
// 4_bit_calc.cpp:78:     putchar(((result >> bit) & 1) ? '1' : '0');
	mov	w0, 49	// iftmp.10_16,
	b	.L35		//
.L34:
// 4_bit_calc.cpp:78:     putchar(((result >> bit) & 1) ? '1' : '0');
	mov	w0, 48	// iftmp.10_16,
.L35:
// 4_bit_calc.cpp:78:     putchar(((result >> bit) & 1) ? '1' : '0');
	bl	putchar		//
// 4_bit_calc.cpp:77:   for (int bit = 3; bit >= 0; --bit) {
	ldr	w0, [sp, 56]	// tmp132, bit
	sub	w0, w0, #1	// bit_42, tmp132,
	str	w0, [sp, 56]	// bit_42, bit
.L33:
// 4_bit_calc.cpp:77:   for (int bit = 3; bit >= 0; --bit) {
	ldr	w0, [sp, 56]	// tmp133, bit
	cmp	w0, 0	// tmp133,
	bge	.L36		//,
// 4_bit_calc.cpp:80:   putchar('\n');
	mov	w0, 10	//,
	bl	putchar		//
// 4_bit_calc.cpp:81:   if (overflow) {
	ldrb	w0, [sp, 45]	// overflow.11_12, overflow
	and	w0, w0, 1	// tmp134, overflow.11_12,
	cmp	w0, 0	// tmp134,
	beq	.L37		//,
// 4_bit_calc.cpp:82:     printf("Overflow\n");
	adrp	x0, .LC5	// tmp135,
	add	x0, x0, :lo12:.LC5	//, tmp135,
	bl	puts		//
.L37:
// 4_bit_calc.cpp:85:   return 0;
	mov	w0, 0	// _15,
.L38:
// 4_bit_calc.cpp:86: }
	ldp	x29, x30, [sp], 64	//,,,
	.cfi_restore 30
	.cfi_restore 29
	.cfi_def_cfa_offset 0
	ret	
	.cfi_endproc
.LFE19:
	.size	main, .-main
	.ident	"GCC: (GNU) 15.2.0"
	.section	.note.GNU-stack,"",@progbits

// Versao minima da calculadora de 4 bits, isolada de qualquer coisa de rede
// (sem webserver, sem HTML). So a aritmetica de 4 bits em complemento de
// dois, lendo operacao e operandos via argv. Feita para ser compilada para
// RISC-V e ARM (aarch64, o ARM do Raspberry Pi 3) e comparar o assembly
// gerado por cada alvo.
//
// Uso:  ./4_bit_calc <add|sub|mul|fac> <A em binario, 4 bits> [B em binario, 4 bits]
// Ex.:  ./4_bit_calc add 0011 0101

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>

int8_t handle_addition(int8_t A, int8_t B, bool* overflow) {
  int8_t result = A + B;
  *overflow = (((A & 0x08) == (B & 0x08)) && ((result & 0x08) != (A & 0x08)));
  return result;
}

int8_t handle_multiplication(int8_t A, int8_t B, bool* overflow) {
  int8_t result = 0;
  if (B & 0x08) {
    B = ((B ^ 0x0F) + 1) & 0x0F;
    A = ((A ^ 0x0F) + 1) & 0x0F;
  }
  for (int i = 0; i < B; ++i) {
    result = handle_addition(result, A, overflow);
    if (*overflow) break;
  }
  return result;
}

int8_t handle_factorial(int8_t A, bool* overflow) {
  int8_t result = 0;
  if (((A & 0x08) == 0x08) || (A == 0)) {
    *overflow = false;
    return 0;
  }
  result = 1;
  for (int i = 2; i <= A; ++i) {
    result = handle_multiplication(result, i, overflow);
    if (*overflow) break;
  }
  return result;
}

int8_t handle_operation(const char* op, int8_t A, int8_t B, bool* overflow) {
  int8_t result;
  if (strcmp(op, "add") == 0) {
    result = handle_addition(A, B, overflow);
  } else if (strcmp(op, "sub") == 0) {
    B = ((B ^ 0x0F) + 1) & 0x0F;
    result = handle_addition(A, B, overflow);
  } else if (strcmp(op, "mul") == 0) {
    result = handle_multiplication(A, B, overflow);
  } else {
    result = handle_factorial(A, overflow);
  }
  return result & 0x0F;
}

int main(int argc, char** argv) {
  if (argc < 3) {
    fprintf(stderr, "uso: %s <add|sub|mul|fac> <A em binario 4 bits> [B em binario 4 bits]\n", argv[0]);
    return 1;
  }

  const char* op = argv[1];
  int8_t A = (int8_t)strtol(argv[2], nullptr, 2);
  int8_t B = (argc > 3) ? (int8_t)strtol(argv[3], nullptr, 2) : 0;

  bool overflow = false;
  int8_t result = handle_operation(op, A, B, &overflow);

  printf("Resultado: ");
  for (int bit = 3; bit >= 0; --bit) {
    putchar(((result >> bit) & 1) ? '1' : '0');
  }
  putchar('\n');
  if (overflow) {
    printf("Overflow\n");
  }

  return 0;
}

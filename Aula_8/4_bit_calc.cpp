#include <iostream>
#include <bitset>
#include "string"
#ifdef MEASURE_TIME
#include <chrono>
#endif

using namespace std;

void print_operation_params(const string& op, int8_t A, int8_t B){
  cout << "\n--- Nova Operacao Recebida ---" << endl;
  cout << "Operacao: "<< op.c_str() << endl;
  cout << "A: "<< bitset<4>(A) << "(" << (int)A << ")" << endl;
  cout << "B: "<< bitset<4>(B) << "(" << (int)B << ")" << endl;
}

int8_t handle_addition(int8_t A, int8_t B, bool* overflow){
  int8_t result = A + B;
  *overflow = (((A & 0x08) == (B & 0x08)) && ((result & 0x08) != (A & 0x08)));
  return result;
}

int8_t handle_multiplication(int8_t A, int8_t B, bool* overflow){
  int8_t result = 0;     
  if (B & 0x08){
    B = ((B ^ 0x0F) +1) & 0x0F;
    A = ((A ^ 0x0F) +1) & 0x0F;
  }
  for(int i = 0; i < B; ++i){
    result = handle_addition(result, A, overflow);
    if(*overflow) break;
  }
  return result;
}

int8_t handle_factorial(int8_t A, bool* overflow){
  int8_t result = 0;
  if((A & 0x08 == 0x08) || (A == 0)){
    *overflow = false;
    return 0;
  }
  result = 1;
  for(int i = 2; i <= A; ++i){
    result = handle_multiplication(result, i, overflow);
    if(*overflow) break;
  }
  return result;
}

int8_t handle_operation(const string& op, int8_t A, int8_t B, bool* overflow){
  int8_t result;
  if(op == "add"){
    result =  handle_addition(A, B, overflow);
  }
  else if(op == "sub"){
    B = ((B^0x0F) + 1) & 0x0F;
    result = handle_addition(A, B, overflow);
  }
  else if(op == "mul"){
    result = handle_multiplication(A, B, overflow);
  }
  else{
    result = handle_factorial(A, overflow);
  }
  return result & 0x0F;
}

int main(){
  int8_t result;
  bool overflow;
  string op_list[] = {"add", "sub", "mul", "fac"};
  for(string op : op_list){
    for(int8_t a = 0; a < 15; ++a){
      for(int8_t b = 0; b < 15; ++b){
        print_operation_params(op, a, b);
#ifdef MEASURE_TIME
        auto start = chrono::high_resolution_clock::now();
#endif
        result = handle_operation(op, a, b, &overflow);
#ifdef MEASURE_TIME
        auto end = chrono::high_resolution_clock::now();
        auto elapsed_ns = chrono::duration_cast<chrono::nanoseconds>(end - start).count();
#endif
        cout << "Resultado: "<< bitset<4>(result);
        if(overflow){
          cout << " [Overflow]";
        }
#ifdef MEASURE_TIME
        cout << " [Tempo: " << elapsed_ns << " ns]";
#endif
        cout<<endl;
      } 
    }
  }
}
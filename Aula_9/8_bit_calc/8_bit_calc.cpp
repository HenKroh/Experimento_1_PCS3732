// Porte do 8_bit_calc.ino (ESP32) para rodar como programa nativo em um
// Raspberry Pi 3 (Linux). Em vez de WiFi.softAP + NetworkServer/NetworkClient,
// usa sockets POSIX e serve na rede já conectada do Pi. Em vez de acender
// LEDs via GPIO, o resultado é impresso no terminal.
//
// Compilar:  g++ -std=c++17 -O2 -o 8_bit_calc 8_bit_calc.cpp
// Executar:  ./8_bit_calc
// Acessar:   http://<IP do Raspberry Pi>:8080/

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <bitset>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <string>

using namespace std;

const int PORT = 8080;

const char* html_page = R"rawliteral(
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>4-Bit Signed Binary Calculator</title>
    <style>
        body {
            font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
            background-color: #eef2f3;
            display: flex;
            justify-content: center;
            align-items: center;
            height: 100vh;
            margin: 0;
        }

        .calculator {
            background-color: #333;
            padding: 20px;
            border-radius: 12px;
            box-shadow: 0 8px 24px rgba(0,0,0,0.3);
            width: 320px;
        }

        /* Result field at the top */
        .result-field {
            background-color: #aed581;
            color: #1b5e20;
            font-family: 'Courier New', Courier, monospace;
            font-size: 2rem;
            font-weight: bold;
            text-align: right;
            padding: 15px;
            border-radius: 6px;
            margin-bottom: 20px;
            min-height: 40px;
            box-shadow: inset 0 2px 5px rgba(0,0,0,0.2);
            word-wrap: break-word;
        }

        .result-field.overflow {
            background-color: #ff8a80;
            color: #b71c1c;
            font-size: 1.5rem;
            text-align: center;
        }

        .main-interface {
            display: flex;
            gap: 15px;
        }

        /* Left Column: Stacked Inputs */
        .inputs-column {
            display: flex;
            flex-direction: column;
            justify-content: space-between;
            flex: 1;
        }

        .inputs-column input {
            width: 100%;
            box-sizing: border-box;
            font-family: 'Courier New', Courier, monospace;
            font-size: 1.6rem;
            padding: 10px;
            text-align: center;
            border: 2px solid #555;
            background-color: #222;
            color: #fff;
            border-radius: 6px;
        }

        .inputs-column input:focus {
            border-color: #aed581;
            outline: none;
        }

        /* Right Column: Square Pad with 4 Buttons */
        .buttons-square {
            display: grid;
            grid-template-columns: repeat(2, 1fr);
            gap: 10px;
            width: 120px;
            padding-top: 20px;
        }

        .buttons-square button {
            background-color: #444;
            color: #fff;
            border: none;
            border-radius: 6px;
            font-size: 1.4rem;
            font-weight: bold;
            cursor: pointer;
            transition: background 0.1s;
        }

        .buttons-square button:hover {
            background-color: #555;
        }

        .buttons-square button:active {
            background-color: #aed581;
            color: #222;
        }

        .label {
            color: #aaa;
            font-size: 0.8rem;
            margin-bottom: 2px;
        }
    </style>
</head>
<body>

<div class="calculator">
    <div class="result-field" id="result">00000000</div>

    <form method="GET" action="/calc">
        <div class="main-interface">
            <div class="inputs-column">
                <div>
                    <div class="label">Binary Num 1</div>
                    <input type="text" name="a" id="num1" pattern="[01]{8}" maxlength="8" placeholder="00000000" required title="Must be an 8-bit binary number (e.g., 01010101)">
                </div>
                <div>
                    <div class="label">Binary Num 2</div>
                    <input type="text" name="b" id="num2" pattern="[01]{8}" maxlength="8" placeholder="00000000" title="Must be an 8-bit binary number (e.g., 11101110)">
                </div>
            </div>

            <div class="buttons-square">
                <button type="submit" name="op" value="add" title="Addition">+</button>
                <button type="submit" name="op" value="sub" title="Subtraction">-</button>
                <button type="submit" name="op" value="mul" title="Multiplication">*</button>
                <button type="submit" name="op" value="fac" title="Factorial (Num 1 only)">!</button>
            </div>
        </div>
    </form>
</div>

<script>
    window.addEventListener('DOMContentLoaded', () => {
        const urlParams = new URLSearchParams(window.location.search);
        const num1Str = urlParams.get('a');
        const num2Str = urlParams.get('b');
        const op = urlParams.get('op');

        const resultDisplay = document.getElementById('result');

        function binToSignedInt(bin) {
            let val = parseInt(bin, 2);
            if (bin[0] === '1') {
                val -= 64;
            }
            return val;
        }

        function signedIntToBin(val) {
            if (val < -128 || val > 127) return "OVERFLOW";
            if (val < 0) {
                val = 256 + val;
            }
            return val.toString(2).padStart(8, '0');
        }

        if (num1Str !== null && op !== null) {
            document.getElementById('num1').value = num1Str;
            if (num2Str) document.getElementById('num2').value = num2Str;

            const binRegex = /^[01]{8}$/;
            if (!binRegex.test(num1Str) || (op !== 'fac' && num2Str && !binRegex.test(num2Str))) {
                resultDisplay.innerText = "ERROR";
                resultDisplay.classList.add('overflow');
                return;
            }

            const n1 = binToSignedInt(num1Str);
            const n2 = (num2Str && num2Str !== "") ? binToSignedInt(num2Str) : 0;

            let decimalResult = 0;
            let isOverflow = false;

            switch(op) {
                case 'add':
                    decimalResult = n1 + n2;
                    break;
                case 'sub':
                    decimalResult = n1 - n2;
                    break;
                case 'mul':
                    decimalResult = n1 * n2;
                    break;
                case 'fac': // FIXED: Matched name 'fac'
                    if (n1 < 0 || n1 > 3) {
                        isOverflow = true;
                    } else {
                        decimalResult = 1;
                        for (let i = 2; i <= n1; i++) {
                            decimalResult *= i;
                        }
                    }
                    break;
                default:
                    return;
            }

            let finalOutput = "";
            if (isOverflow) {
                finalOutput = "OVERFLOW";
            } else {
                finalOutput = signedIntToBin(decimalResult);
            }

            resultDisplay.innerText = finalOutput;
            if (finalOutput === "OVERFLOW") {
                resultDisplay.classList.add('overflow');
            } else {
                resultDisplay.classList.remove('overflow');
            }
        }
    });
</script>

</body>
</html>
)rawliteral";

uint32_t micros() {
  using namespace std::chrono;
  return (uint32_t)duration_cast<microseconds>(
             steady_clock::now().time_since_epoch())
      .count();
}

void send_html_page(int client_fd) {
  string response = "HTTP/1.1 200 OK\r\nContent-type:text/html\r\n\r\n";
  response += html_page;
  response += "\r\n";
  send(client_fd, response.c_str(), response.size(), 0);
}

void get_operation_params(const string& request, string* op, uint16_t* A,
                           uint16_t* B) {
  size_t indexA = request.find("a=");
  size_t indexB = request.find("&b=");
  size_t indexOp = request.find("&op=");

  if (indexA != string::npos && indexB != string::npos &&
      indexOp != string::npos) {
    size_t indexEnd = request.find(' ', indexOp);
    string strA = request.substr(indexA + 2, indexB - (indexA + 2));
    string strB = request.substr(indexB + 3, indexOp - (indexB + 3));
    string strOp = request.substr(
        indexOp + 4, (indexEnd != string::npos ? indexEnd : request.size()) -
                         (indexOp + 4));
    while (!strOp.empty() && isspace((unsigned char)strOp.back()))
      strOp.pop_back();
    while (!strOp.empty() && isspace((unsigned char)strOp.front()))
      strOp.erase(strOp.begin());

    *A = strtol(strA.c_str(), NULL, 2);
    *B = strtol(strB.c_str(), NULL, 2);
    *op = strOp;
  }
}

void print_operation_params(const string& op, uint16_t A, uint16_t B) {
  cout << "\n--- Nova Operacao Recebida ---" << endl;
  printf("Operacao: %s\n", op.c_str());
  cout << "A: " << bitset<8>(A & 0xFF) << endl;
  cout << "B: " << bitset<8>(B & 0xFF) << endl;
}

uint16_t handle_addition(uint16_t A, uint16_t B, bool* overflow) {
  uint16_t result = A + B;
  *overflow =
      (((A & 0x80) == (B & 0x80)) && ((result & 0x80) != (A & 0x80)));
  return result;
}

uint16_t handle_multiplication(uint16_t A, uint16_t B, bool* overflow) {
  uint16_t result = 0;
  if (B & 0x80) {
    B = ((B ^ 0xFF) + 1) & 0xFF;
    A = ((A ^ 0xFF) + 1) & 0xFF;
  }

  uint32_t start_time = micros();
  for (int i = 0; i < B; ++i) {
    result = handle_addition(result, A, overflow);
    if (*overflow) break;
  }

  uint32_t end_time = micros();
  uint32_t elapsed_time = end_time - start_time;
  printf("Multiplication Time: %u microseconds\n", elapsed_time);

  return result;
}

uint16_t handle_factorial(uint16_t A, bool* overflow) {
  uint16_t result = 0;
  if (((A & 0x80) == 0x80) || (A == 0)) {
    *overflow = false;
    return 0;
  }
  result = 1;
  uint32_t start_time = micros();
  for (int i = 2; i <= A; ++i) {
    result *= i;
  }
  uint32_t end_time = micros();
  uint32_t elapsed_time = end_time - start_time;
  printf("Factorial Time: %u microseconds\n", elapsed_time);
  return result;
}

uint16_t handle_operation(const string& op, uint16_t A, uint16_t B,
                           bool* overflow) {
  uint16_t result;
  if (op == "add") {
    result = handle_addition(A, B, overflow);
  } else if (op == "sub") {
    B = ((B ^ 0xFF) + 1) & 0xFF;
    result = handle_addition(A, B, overflow);
  } else if (op == "mul") {
    result = handle_multiplication(A, B, overflow);
  } else {
    result = handle_factorial(A, overflow);
  }
  return result & 0xFF;
}

int main() {
  int server_fd = socket(AF_INET, SOCK_STREAM, 0);
  if (server_fd < 0) {
    perror("socket");
    return 1;
  }

  int opt = 1;
  setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

  sockaddr_in address{};
  address.sin_family = AF_INET;
  address.sin_addr.s_addr = INADDR_ANY;
  address.sin_port = htons(PORT);

  if (bind(server_fd, (sockaddr*)&address, sizeof(address)) < 0) {
    perror("bind");
    return 1;
  }
  if (listen(server_fd, 5) < 0) {
    perror("listen");
    return 1;
  }

  cout << "Servidor rodando. Acesse http://<IP do Raspberry Pi>:" << PORT
       << "/" << endl;

  while (true) {
    sockaddr_in client_addr{};
    socklen_t client_len = sizeof(client_addr);
    int client_fd =
        accept(server_fd, (sockaddr*)&client_addr, &client_len);
    if (client_fd < 0) continue;

    cout << "New Client." << endl;
    string currentLine;
    char c;
    while (true) {
      ssize_t n = recv(client_fd, &c, 1, 0);
      if (n <= 0) break;
      if (c == '\n') {
        if (currentLine.find("GET /calc?") != string::npos) {
          bool overflow = false;
          uint16_t A, B, result;
          string op;

          get_operation_params(currentLine, &op, &A, &B);
          print_operation_params(op, A, B);
          result = handle_operation(op, A, B, &overflow);
          cout << "Resultado: " << bitset<8>(result & 0xFF) << endl;
          if (overflow) {
            cout << "Ocorreu Overflow na operacao!" << endl;
          }
          cout << "-------------------------------\n" << endl;
        }
        if (currentLine.empty()) {
          send_html_page(client_fd);
          break;
        } else {
          currentLine.clear();
        }
      } else if (c != '\r') {
        currentLine += c;
      }
    }
    close(client_fd);
    cout << "Client Disconnected." << endl;
  }

  close(server_fd);
  return 0;
}

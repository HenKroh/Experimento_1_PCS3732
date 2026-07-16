// Porte do n_bit_calc.ino (ESP32) para rodar como programa nativo em um
// Raspberry Pi 3 (Linux). Em vez de WiFi.softAP + NetworkServer/NetworkClient,
// usa sockets POSIX e serve na rede já conectada do Pi. Em vez de acender
// LEDs via GPIO, o resultado é impresso no terminal.
//
// Compilar:  g++ -std=c++17 -O2 -o n_bit_calc n_bit_calc.cpp
// Executar:  ./n_bit_calc
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
            /* height: 120px; */
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
    <div class="result-field" id="result">0000</div>

    <form method="GET" action="">
        <div class="main-interface">
            <div class="inputs-column">
                <div>
                    <div class="label">Binary Num 1</div>
                    <input type="text" name="num1" id="num1" placeholder="0000" required title="Must be a 4-bit binary number (e.g., 0101)">
                </div>
                <div>
                    <div class="label">Binary Num 2</div>
                    <input type="text" name="num2" id="num2" placeholder="0000" title="Must be a 4-bit binary number (e.g., 1110)">
                </div>
            </div>
                <div>
                    <div class="label">Bits number</div>
                    <input type="text" name="bits" id="bits" maxlength="1" placeholder="0" title="Must be equal or less then 8">
                </div>
            <div class="buttons-square">
                <button type="submit" name="op" value="add" title="Addition">+</button>
                <button type="submit" name="op" value="sub" title="Subtraction">-</button>
                <button type="submit" name="op" value="mul" title="Multiplication">*</button>
                <button type="submit" name="op" value="fact" title="Factorial (Num 1 only)">!</button>
            </div>
        </div>
    </form>
</div>

<script>
    window.addEventListener('DOMContentLoaded', () => {
        // Parse GET parameters from URL
        const urlParams = new URLSearchParams(window.location.search);
        const num1Str = urlParams.get('num1');
        const num2Str = urlParams.get('num2');
        const op = urlParams.get('op');
        const bitsStr = urlParams.get('bits');

        const resultDisplay = document.getElementById('result');

        function getBitConfig(bitsValue) {
            const bits = bitsValue ? parseInt(bitsValue, 10) : 4;
            if (!Number.isInteger(bits) || bits < 1 || bits > 8) return null;

            const width = 1 << bits;
            return {
                bits,
                width,
                mask: width - 1,
                signMask: 1 << (bits - 1),
                min: -(1 << (bits - 1)),
                max: (1 << (bits - 1)) - 1
            };
        }

        // Helper: Convert n-bit signed binary (Two's Complement) to Decimal
        function binToSignedInt(bin, config) {
            const val = parseInt(bin, 2) & config.mask;
            return (val & config.signMask) ? val - config.width : val;
        }

        function signedIntToUnsigned(val, config) {
            return val & config.mask;
        }

        function unsignedToBin(val, config) {
            return (val & config.mask).toString(2).padStart(config.bits, '0');
        }

        function addBits(A, B, config) {
            const result = (A + B) & config.mask;
            const overflow = ((A & config.signMask) === (B & config.signMask)) &&
                             ((result & config.signMask) !== (A & config.signMask));
            return { result, overflow };
        }

        function twosComplement(value, config) {
            return ((value ^ config.mask) + 1) & config.mask;
        }

        function multiplyBits(A, B, config) {
            let result = 0;
            let overflow = false;

            if (B & config.signMask) {
                B = twosComplement(B, config);
                A = twosComplement(A, config);
            }

            for (let i = 0; i < B; i++) {
                const sum = addBits(result, A, config);
                result = sum.result;
                overflow = sum.overflow;
                if (overflow) break;
            }

            return { result, overflow };
        }

        // If the form was submitted via GET
        if (num1Str !== null && op !== null) {
            // Repopulate fields for seamless UX
            document.getElementById('num1').value = num1Str;
            if (num2Str) document.getElementById('num2').value = num2Str;
            if (bitsStr) document.getElementById('bits').value = bitsStr;

            const config = getBitConfig(bitsStr);

            // Simple validation regex
            const binRegex = config ? new RegExp(`^[01]{${config.bits}}$`) : null;
            if (!config || !binRegex.test(num1Str) || (op !== 'fact' && !binRegex.test(num2Str))) {
                resultDisplay.innerText = "ERROR";
                resultDisplay.classList.add('overflow');
                return;
            }

            const n1 = binToSignedInt(num1Str, config);
            const n2 = num2Str ? binToSignedInt(num2Str, config) : 0;
            const A = signedIntToUnsigned(n1, config);
            const B = signedIntToUnsigned(n2, config);

            let bitResult = 0;
            let isOverflow = false;

            // Perform chosen operation
            switch(op) {
                case 'add':
                    {
                        const result = addBits(A, B, config);
                        bitResult = result.result;
                        isOverflow = result.overflow;
                    }
                    break;
                case 'sub':
                    {
                        const result = addBits(A, twosComplement(B, config), config);
                        bitResult = result.result;
                        const decimalResult = n1 - n2;
                        isOverflow = decimalResult < config.min || decimalResult > config.max;
                    }
                    break;
                case 'mul':
                    {
                        const result = multiplyBits(A, B, config);
                        bitResult = result.result;
                        const decimalResult = n1 * n2;
                        isOverflow = result.overflow || decimalResult < config.min || decimalResult > config.max;
                    }
                    break;
                case 'fact':
                    if (n1 < 0) {
                        isOverflow = true;
                    } else {
                        bitResult = 1;
                        for (let i = 2; i <= n1; i++) {
                            const result = multiplyBits(bitResult, signedIntToUnsigned(i, config), config);
                            bitResult = result.result;
                            isOverflow = result.overflow;
                            if (isOverflow) break;
                        }
                    }
                    break;
                default:
                    return;
            }

            // Determine final output string
            let finalOutput = "";
            if (isOverflow) {
                finalOutput = "OVERFLOW";
            } else {
                finalOutput = unsignedToBin(bitResult, config);
            }

            // Display results
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

void get_operation_params(const string& request, string* op, int16_t* A,
                           int16_t* B, int16_t* bits) {
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
    *bits = strtol(strB.c_str(), NULL, 10);
    *op = strOp;
  }
}

void print_operation_params(const string& op, int16_t A, int16_t B) {
  cout << "\n--- Nova Operacao Recebida ---" << endl;
  printf("Operacao: %s\n", op.c_str());
  cout << "A: " << bitset<16>((uint16_t)A) << endl;
  cout << "B: " << bitset<16>((uint16_t)B) << endl;
}

// NOTE: o .ino original declarava "unsigned int16_t", que nao compila em
// C++ (o especificador "unsigned" nao pode ser combinado com um typedef).
// Trocado para uint16_t para o porte compilar, mantendo o mesmo comportamento.
int16_t handle_addition(int16_t A, int16_t B, bool* overflow, int16_t bits) {
  int16_t result = A + B;
  uint16_t mask = 1U << (bits - 1);
  *overflow = (((A & mask) == (B & mask)) && ((result & mask) != (A & mask)));
  return result;
}

int16_t handle_multiplication(int16_t A, int16_t B, bool* overflow,
                               int16_t bits) {
  int16_t result = 0;
  uint16_t mask = (1U << bits) - 1;
  if (B & 0x08) {
    B = ((B ^ mask) + 1) & mask;
    A = ((A ^ mask) + 1) & mask;
  }
  for (int i = 0; i < B; ++i) {
    result = handle_addition(result, A, overflow, bits);
    if (*overflow) break;
  }
  return result;
}

int16_t handle_factorial(int16_t A, bool* overflow, int16_t bits) {
  int16_t result = 0;
  int16_t mask = 1 << (bits - 1);

  if (((A & mask) == mask) || (A == 0)) {
    *overflow = false;
    return 0;
  }
  result = 1;
  for (int i = 2; i <= A; ++i) {
    result = handle_multiplication(result, i, overflow, bits);
    if (*overflow) break;
  }
  return result;
}

int16_t handle_operation(const string& op, int16_t A, int16_t B,
                          bool* overflow, int16_t bits) {
  int16_t result;
  int16_t mask = 1 << (bits - 1);

  if (op == "add") {
    result = handle_addition(A, B, overflow, bits);
  } else if (op == "sub") {
    B = ((B ^ mask) + 1) & mask;
    result = handle_addition(A, B, overflow, bits);
  } else if (op == "mul") {
    result = handle_multiplication(A, B, overflow, bits);
  } else {
    result = handle_factorial(A, overflow, bits);
  }
  return result & mask;
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
          int16_t A, B, result, bits;
          string op;

          get_operation_params(currentLine, &op, &A, &B, &bits);
          print_operation_params(op, A, B);
          result = handle_operation(op, A, B, &overflow, bits);

          cout << "Resultado: " << bitset<16>((uint16_t)result) << endl;
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

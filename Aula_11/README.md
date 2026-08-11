# SmartLock — fechadura eletrônica com autorização por BLE

**PCS3732 — Laboratório de Processadores · Escola Politécnica da USP · Grupo D**

Fechadura eletrônica controlada por uma Raspberry Pi que autoriza a abertura a partir de
aplicativos iOS e Android por Bluetooth Low Energy. Um celular novo só é cadastrado se alguém
apertar o botão físico na fechadura; a partir daí, cada abertura exige uma prova
criptográfica de posse do segredo — desafio–resposta com HMAC-SHA256 —, de modo que um pacote
capturado do rádio não serve para abrir a porta depois.

O relatório completo do projeto está em [`docs/relatorio.md`](docs/relatorio.md).

---

## Organização das pastas

```text
Aula_11/
├── README.md                 este arquivo
├── LICENSE                   GNU General Public License v3.0
├── .gitignore                complementa o .gitignore da raiz do repositório
│
├── docs/
│   ├── relatorio.md          relatório do projeto (9 seções, base para a versão em LaTeX)
│   ├── rastreabilidade.md    matriz requisitos × casos de teste
│   ├── diagramas/            fontes editáveis dos diagramas, em D2
│   ├── figuras/              figuras exportadas (SVG e PNG) — geradas, não editar à mão
│   └── evidencias/           saídas de execução dos testes e registros de bancada
│
├── protocol/                 especificação normativa comum às três implementações
│   ├── uuids.md              serviço e características GATT
│   ├── messages.md           formato das mensagens (JSON/UTF-8, Base64)
│   ├── state-machine.md      estados e transições que as três pontas devem respeitar
│   └── security.md           modelo de ameaça, garantias e limitações conhecidas
│
└── src/
    ├── raspberry/            periférico BLE (Python) — a fechadura
    │   ├── smartlock/        código do serviço
    │   ├── tests/            testes automatizados (unittest)
    │   ├── smartlock.service unidade systemd
    │   └── README.md         instalação, ligações, configuração e diagnóstico
    │
    ├── ios/                  central BLE (Swift / SwiftUI + Core Bluetooth)
    │   └── README.md         build, assinatura e instalação no iPhone
    │
    └── android/              central BLE (Kotlin / Jetpack Compose + BluetoothGatt)
        └── README.md         build, execução e diferenças em relação ao iOS
```

As três implementações em `src/` são independentes e não compartilham código: o que as mantém
compatíveis é a especificação em [`protocol/`](protocol/), mantida fora de `src/` por ser
documento normativo, e não código. Qualquer alteração ali precisa ser refletida nas três.

---

## Como rodar

### 1. A fechadura (Raspberry Pi)

```sh
sudo apt install python3-dbus python3-gi bluez python3-rpi.gpio
cd src/raspberry
sudo python3 -m smartlock run
```

Sem hardware, em qualquer máquina com BlueZ (os botões passam a ser as teclas `a` e `d`):

```sh
python3 -m smartlock run --no-gpio
```

Administração:

```sh
python3 -m smartlock devices              # celulares cadastrados
python3 -m smartlock revoke <deviceId>    # revoga um celular
python3 -m smartlock log --limit 50       # últimos acessos
```

Ligações, chaves DIP da placa Freenove, variáveis de configuração e o contorno de anúncio BLE
estão detalhados em [`src/raspberry/README.md`](src/raspberry/README.md).

### 2. O aplicativo Android

```sh
cd src/android
./gradlew installDebug     # aparelho físico: o emulador não tem rádio BLE
```

Detalhes em [`src/android/README.md`](src/android/README.md).

### 3. O aplicativo iOS

Abrir `src/ios/SmartLock.xcodeproj` no Xcode 16+, escolher o *Team* de assinatura e rodar em
um iPhone com iOS 17+. No Simulador o app roda contra uma fechadura simulada, o que permite
percorrer todo o fluxo sem hardware. Detalhes em [`src/ios/README.md`](src/ios/README.md).

### 4. Uso

1. Com a fechadura anunciando, abra o aplicativo e conceda as permissões de Bluetooth.
2. Toque na fechadura que aparecer na lista.
3. Toque em **Solicitar acesso** e, na Raspberry, aperte o botão **Permitir** (S4).
4. Cadastrado o aparelho, o botão **Desbloquear** passa a valer.

---

## Testes

```sh
# Raspberry Pi — 15 casos, sem BLE, sem GPIO e sem esperar tempo real
cd src/raspberry && python3 -m unittest discover -s tests -t . -v

# Android — 37 casos na JVM, sem aparelho
cd src/android && ./gradlew test
```

A matriz que liga cada requisito aos casos de teste está em
[`docs/rastreabilidade.md`](docs/rastreabilidade.md), incluindo as lacunas conhecidas de
cobertura. Evidências da última execução em [`docs/evidencias/`](docs/evidencias/).

---

## Diagramas

As fontes estão em [`docs/diagramas/`](docs/diagramas/), em [D2](https://d2lang.com/). Para
regenerar as figuras após editar:

```sh
cd docs/diagramas
for f in *.d2; do d2 "$f" "../figuras/${f%.d2}.svg"; done
```

---

## Licença

Distribuído sob a [GNU General Public License v3.0](LICENSE).

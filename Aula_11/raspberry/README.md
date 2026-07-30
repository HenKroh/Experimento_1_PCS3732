# SmartLock — serviço da Raspberry Pi

Periférico BLE que fala o mesmo protocolo do app iOS (`../ios`): cadastro
aprovado por botão físico e desbloqueio por desafio–resposta com HMAC-SHA256.
Fases 1, 2 e 5 do plano.

Substitui o `rasp_lock_server.py` original, que usava Bluetooth clássico
(RFCOMM, via `bluedot`) e mandava a chave em texto puro nos dois sentidos. O app
iOS é uma central **BLE** e não enxerga RFCOMM, então transporte e protocolo
tiveram de mudar juntos.

## Dependências

Na Raspberry Pi OS:

```sh
sudo apt install python3-dbus python3-gi bluez
sudo apt install python3-rpi.gpio     # já vem na imagem completa
```

São pacotes do sistema de propósito: `dbus-python` e `PyGObject` compilam contra
bibliotecas nativas e instalá-los por `pip` num venv costuma dar mais trabalho do
que ajuda. Se usar venv, crie com `--system-site-packages`.

Nada além da biblioteca padrão é necessário para rodar os testes ou os comandos
de administração.

## Rodar

```sh
cd raspberry
sudo python3 -m smartlock run
```

Precisa de root (ou de uma política D-Bus própria) para registrar serviço GATT e
anúncio no `bluetoothd`.

Sem hardware — num PC, ou na Pi antes de soldar nada:

```sh
python3 -m smartlock run --no-gpio     # 'a' + Enter aprova, 'd' + Enter nega
```

Ainda assim é preciso BlueZ com adaptador BLE; só os botões, LEDs e o relé é que
viram log.

## Administração

```sh
python3 -m smartlock devices              # celulares cadastrados
python3 -m smartlock revoke <deviceId>    # revoga um celular
python3 -m smartlock log --limit 50       # últimos acessos
```

O `deviceId` é o que aparece em `devices` e nas notificações do app.

## Ligações

Os padrões são os da **Freenove Projects Board for Raspberry Pi** usada na
bancada. Nessa placa os periféricos estão em pinos fixos, então os valores
abaixo não são uma escolha nossa — são o que a placa oferece. Numeração BCM;
tudo configurável por variável de ambiente.

| Função | GPIO | Onde fica na placa | Variável |
| --- | --- | --- | --- |
| Botão Permitir | 26 | botão **S4** (amarelo) | `SMARTLOCK_BUTTON_APPROVE_PIN` |
| Botão Negar | 21 | botão **S5** (vermelho) | `SMARTLOCK_BUTTON_DENY_PIN` |
| LED aguardando | 5 | conector RGB LED, pino R | `SMARTLOCK_LED_WAITING_PIN` |
| LED aprovado | 6 | conector RGB LED, pino G | `SMARTLOCK_LED_APPROVED_PIN` |
| LED negado | 13 | conector RGB LED, pino B | `SMARTLOCK_LED_DENIED_PIN` |
| LED destravado | 17 | **Blue LED** soldado na placa | `SMARTLOCK_LED_UNLOCKED_PIN` |
| Relé | 12 | relé **K1** | `SMARTLOCK_ACTUATOR_PIN` |
| Sensor de porta | — | — | `SMARTLOCK_DOOR_SENSOR_PIN` |

Os outros dois botões, se quiser trocar: **S6** (azul) é GPIO20 e **S7** (verde)
é GPIO16.

### Function Selection Switch

A placa multiplexa os periféricos em duas chaves DIP. Sem isto, os GPIOs acima
não chegam a lugar nenhum:

| Bloco | Posição | Estado | Por quê |
| --- | --- | --- | --- |
| S3 | 5, 6, 7, 8 | **ON** | grupo *2-Button* — liga os quatro botões |
| S2 | 2 | **ON** | *4-Relay* |
| S2 | 3 | **ON** | *5-Blue LED* |
| S2 | 1 | **OFF** | *3-Active Buzzer* divide o GPIO12 com o relé |

Deixe *7-LED Matrix*, *8-7-Segment LED* e *9-LED Bar Graph* (S2 5, 6, 7)
desligados: os três 74HC595 ocupam GPIO17, 27 e 22, e o 17 é o nosso LED azul.
*1-Stepping Motor* (S3 1–4) também precisa ficar desligado — divide pinos com o
conector do RGB LED.

### Polaridade

Botões da placa vão do GPIO ao terra e usamos o pull-up interno: repouso em
nível alto, pressionado em nível baixo, borda de descida com debounce de 300 ms.

O conector *RGB LED* é de **anodo comum** (o pino comum vai ao 5V), então um
módulo ligado ali acende em nível baixo — nesse caso use
`SMARTLOCK_LED_ACTIVE_LOW=1`. O LED azul da placa é normal (acende em nível
alto), e o padrão do serviço é nível alto; sem módulo RGB conectado, os três
LEDs de status ainda aparecem nos indicadores por GPIO da régua de pinos.

O relé da placa aciona em nível alto — o padrão. Para módulos de relé externos
de lógica invertida: `SMARTLOCK_ACTUATOR_ACTIVE_HIGH=0`. Fechadura de verdade
**precisa de fonte separada**: um solenoide alimentado pelos 3V3 da Pi derruba a
placa no acionamento.

## Configuração

Tudo tem padrão razoável; sobrescreva pelo ambiente com o prefixo `SMARTLOCK_`.

| Variável | Padrão | O que é |
| --- | --- | --- |
| `LOCK_ID` | `lock-01` | identidade estável; é a chave usada no Keychain do app |
| `LOCK_NAME` | `Fechadura da Sala` | nome exibido no app |
| `ADVERTISED_NAME` | `SmartLock-Sala` | nome curto no anúncio BLE |
| `DATABASE` | `smartlock.db` | caminho do SQLite |
| `CHALLENGE_TTL` | `5` | validade do nonce, em segundos |
| `ENROLLMENT_TIMEOUT` | `55` | espera pelo botão físico |
| `UNLOCK_DURATION` | `5` | tempo máximo de abertura |
| `MAX_FAILED_ATTEMPTS` | `3` | tentativas antes do bloqueio |
| `LOCKOUT_DURATION` | `30` | duração do bloqueio |
| `REQUIRE_ENCRYPTION` | `0` | exige emparelhamento com link criptografado |
| `USE_GPIO` | `1` | desligue para rodar sem hardware |
| `LED_ACTIVE_LOW` | `0` | LEDs de status em anodo comum (módulo RGB) |

Trocar `LOCK_ID` faz o app tratar a fechadura como outra e pedir cadastro de
novo — a credencial guardada é indexada por ele.

## Estrutura

```text
smartlock/
├── protocol.py          contrato compartilhado (UUIDs, mensagens)
├── authentication.py    desafio–resposta, nonce, sessão, rate limit
├── database.py          SQLite: devices + access_log
├── enrollment.py        fila de solicitações pendentes
├── gpio.py              botões e LEDs (com fallback sem hardware)
├── lock_controller.py   relé/servo e retravamento automático
├── service.py           as regras, sem D-Bus nem GPIO
├── runner.py            loop GLib, registro no BlueZ
└── ble_server/          periférico BLE
    ├── bluez.py         mecânica D-Bus do GATT
    ├── advertisement.py anúncio
    └── gatt.py          as sete características
```

`service.py` não importa D-Bus nem `RPi.GPIO`: é o que permite exercitar o
protocolo inteiro nos testes, em qualquer máquina.

Threads: tudo corre no loop GLib. Callbacks vindos de fora (botões do
`RPi.GPIO`, timer do atuador) voltam para esse loop pelo `GLibScheduler`, então
o estado do serviço nunca é tocado de dois lugares ao mesmo tempo.

## Testes

```sh
cd raspberry
python3 -m unittest discover -s tests -t .
```

Cobrem o caminho feliz e as recusas principais. Não dependem de BLE, de GPIO nem
do relógio de parede — o tempo é virtual.

Use `-s tests`, não `discover` na raiz: a descoberta ampla tenta importar
`ble_server`, que precisa de `dbus`.

## Verificar com uma ferramenta genérica de BLE

Antes de mexer no app, dá para conferir o serviço com o `bluetoothctl` ou com o
nRF Connect: a fechadura deve aparecer como `SmartLock-Sala`, expor o serviço
`a1b20001-…` com sete características, e a leitura de `a1b20002-…` deve devolver
o JSON do Device Information.

## Protocolo

A especificação compartilhada com iOS e Android está em `../protocol`:
`uuids.md`, `messages.md`, `state-machine.md` e `security.md`.

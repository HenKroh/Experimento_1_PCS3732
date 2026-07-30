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

Padrão em BCM; tudo configurável por variável de ambiente.

| Função | GPIO | Variável |
| --- | --- | --- |
| Botão Permitir | 17 | `SMARTLOCK_BUTTON_APPROVE_PIN` |
| Botão Negar | 27 | `SMARTLOCK_BUTTON_DENY_PIN` |
| LED aguardando | 5 | `SMARTLOCK_LED_WAITING_PIN` |
| LED aprovado | 6 | `SMARTLOCK_LED_APPROVED_PIN` |
| LED negado | 13 | `SMARTLOCK_LED_DENIED_PIN` |
| LED destravado | 19 | `SMARTLOCK_LED_UNLOCKED_PIN` |
| Relé / servo | 22 | `SMARTLOCK_ACTUATOR_PIN` |
| Sensor de porta | — | `SMARTLOCK_DOOR_SENSOR_PIN` |

Botões entre o GPIO e o terra, usando o pull-up interno: repouso em nível alto,
pressionado em nível baixo, detecção na borda de descida com debounce de 300 ms.
LEDs com resistor de série. O atuador **precisa de fonte separada** — um relé
alimentado pelos 3V3 da Pi derruba a placa no acionamento.

Para relé com lógica invertida (comum nos módulos de 1 canal):
`SMARTLOCK_ACTUATOR_ACTIVE_HIGH=0`.

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

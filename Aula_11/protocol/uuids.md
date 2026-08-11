# UUIDs

Gerados para este projeto. Todos os sufixos são `-5F6D-4C3E-9A2B-7E8F0D1C2B3A`.

| Papel | UUID | Propriedades |
| --- | --- | --- |
| Smart Lock Service | `A1B20001-…` | primary |
| Device Information | `A1B20002-…` | read |
| Access Request | `A1B20003-…` | write with response |
| Approval Status | `A1B20004-…` | notify |
| Authentication Challenge | `A1B20005-…` | read |
| Authentication Response | `A1B20006-…` | write with response |
| Unlock Command | `A1B20007-…` | write with response |
| Operation Result | `A1B20008-…` | notify |

## Onde cada implementação declara isso

| | Arquivo |
| --- | --- |
| Raspberry | `src/raspberry/smartlock/protocol.py` (minúsculas, como o BlueZ exige) |
| iOS | `src/ios/SmartLock/Protocol/LockProtocol.swift` |
| Android | `src/android/app/src/main/java/com/example/locker/protocol/LockProtocol.kt` |

## Anúncio

O periférico anuncia com `ServiceUUIDs = [A1B20001-…]` e `LocalName` igual ao
nome curto da fechadura (padrão `SmartLock-Sala`).

O UUID do serviço **precisa** estar no pacote de anúncio: o app iOS chama
`scanForPeripherals(withServices: [service])` e o Core Bluetooth filtra pelo que
foi anunciado, não pelo que existe no GATT. Sem ele a fechadura não aparece na
lista.

## Escrita com resposta

As três características de escrita usam *write with response*. O celular precisa
saber que a mensagem chegou antes de esperar a notificação correspondente, e o
`Write Without Response` não dá essa garantia.

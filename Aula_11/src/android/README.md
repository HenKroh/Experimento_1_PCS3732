# SmartLock — app Android

Central BLE que faz o cadastro por botão físico e o desbloqueio por
desafio–resposta contra a Raspberry Pi. Fase 3 do plano, e a parte da fase 5 que
cabe ao celular.

Fala exatamente o mesmo protocolo do app iOS — mesmos UUIDs, mesmo JSON, mesmo
HMAC. A especificação está em [`../../protocol/`](../../protocol/); o app iOS
equivalente, em [`../ios/`](../ios/).

## Setup

Android Studio (Ladybug ou mais novo) ou apenas o SDK de linha de comando, com:

- JDK 17+
- Android SDK Platform 37 e Build-Tools correspondentes
- Um aparelho **físico**: o emulador não tem rádio BLE, então não enxerga a
  fechadura

Crie `local.properties` apontando para o SDK (o Android Studio faz isso sozinho):

```properties
sdk.dir=/caminho/para/Android/sdk
```

## Rodar

```sh
./gradlew installDebug      # instala no aparelho conectado via adb
./gradlew test              # testes de unidade (JVM, sem aparelho)
./gradlew assembleDebug     # só gera o APK
```

Com a fechadura ligada e anunciando (`raspberry/README.md`), abra o app:

1. Conceda as permissões de Bluetooth quando pedidas.
2. A fechadura aparece na lista **Por perto** — toque nela.
3. **Solicitar acesso** e, na Raspberry, aperte o botão **Permitir**.
4. Feito o cadastro, o botão **Desbloquear** passa a valer.

## Estrutura

| Pasta | Papel |
| --- | --- |
| `protocol/` | UUIDs e mensagens; espelha `protocol/` e `LockProtocol.swift` |
| `crypto/` | `HMAC-SHA256(secret, "unlock" \|\| 0 \|\| deviceId \|\| 0 \|\| nonce)` |
| `ble/` | `LockTransport` e a implementação sobre `BluetoothGatt` |
| `storage/` | Credenciais cifradas com chave do Android Keystore |
| `domain/` | `LockManager`: máquina de estados de cadastro e desbloqueio |
| `ui/` | Telas em Jetpack Compose |

O `LockManager` é o mesmo desenho do `LockManager.swift`: quem mexer em um
precisa mexer no outro.

## Diferenças em relação ao iOS

Não são escolhas de estilo — são imposições da plataforma.

**MTU.** O Core Bluetooth negocia 185 bytes sozinho; o Android fica nos 23 bytes
do ATT padrão até alguém pedir mais. O `Approval Status` aprovado tem ~140 bytes
e chegaria truncado, então `BleLockTransport` chama `requestMtu(185)` logo após
conectar. Se a fechadura recusar, o app segue com o MTU que houver e recusa
escrever mensagem que não caiba, em vez de deixar truncar em silêncio.

**Uma operação GATT por vez.** A pilha do Android descarta a segunda operação
concorrente sem avisar; o Core Bluetooth enfileira. Daí o `Mutex` que serializa
leitura, escrita e assinatura de notificação.

**Threads.** Os callbacks do `BluetoothGattCallback` chegam em threads de
binder, não na main. Todo o estado do transporte fica atrás de um lock.

**Notificações.** É preciso escrever o descritor CCCD (`0x2902`) além de chamar
`setCharacteristicNotification`. No iOS, `setNotifyValue` faz as duas coisas.

**Armazenamento.** Sem Keychain: as credenciais vão para `SharedPreferences`
cifradas em AES-256-GCM com uma chave gerada dentro do Android Keystore, que
nunca sai do aparelho. `allowBackup=false`, porque a chave não vai no backup e o
dado restaurado seria ilegível de qualquer forma.

**minSdk 26.** Dá `java.util.Base64`, a mesma codificação que o protocolo usa,
e que — ao contrário de `android.util.Base64` — funciona nos testes de JVM.

## Testes

`./gradlew test` cobre o que dá para verificar sem rádio:

- **`LockCryptoTest`** — o HMAC contra vetores gerados pela própria Raspberry
  (`raspberry/smartlock/authentication.py`). Se este teste quebrar, o
  desbloqueio quebrou.
- **`LockCodecTest`** — cada mensagem do protocolo, usando JSON literal emitido
  pelo `protocol.py`. Inclui a recusa de versão diferente e o caso do
  `Approval Status` truncado por MTU pequeno.
- **`LockManagerTest`** — cadastro aprovado, negado e com timeout; desbloqueio
  feliz; autenticação negada, com bloqueio temporário e com falha do atuador;
  remoção de credencial. Usa `FakeLockTransport`, que faz o papel do
  `MockLockTransport` do iOS.

Os cenários que dependem de rádio e de hardware — dois celulares ao mesmo tempo,
Bluetooth desligado no meio da operação, revogação, reinício da Raspberry — estão
na lista da seção 9 do plano e só se testam em bancada.

## Limitações

As mesmas de `../../protocol/security.md`, em especial o segredo difundido no
instante do cadastro. Além delas:

- O desbloqueio exige o app aberto; não há serviço em segundo plano.
- Não há reconexão automática: caiu a conexão, o usuário volta e toca de novo.
- A lista marca "já cadastrada" comparando o **nome** anunciado, porque o
  `lockId` só chega depois de conectar. É dica visual, não garantia.

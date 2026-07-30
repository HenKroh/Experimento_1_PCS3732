# SmartLock — app iOS

Central BLE que faz o cadastro por botão físico e o desbloqueio por desafio–resposta
contra a Raspberry Pi. Fases 4 e parte da 5 do plano.

## Setup

Xcode 16 ou superior; alvo mínimo iOS 17. Depois de instalar o Xcode, aponte as
ferramentas de linha de comando para ele:

```sh
sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
sudo xcodebuild -license accept
```

**Platform iOS** (~8,5 GB) — o Xcode 26 não instala junto:

```sh
xcodebuild -downloadPlatform iOS
```

Necessário para o Simulador. Para build de device dá para contornar (ver abaixo).

**Assinatura** — Xcode → Settings → Accounts → `+` → Apple ID. Conta gratuita
funciona; o app expira em 7 dias. Em seguida, target **SmartLock** →
*Signing & Capabilities* → *Automatically manage signing* → escolha seu **Team**.
Isso grava `DEVELOPMENT_TEAM` no `.pbxproj`, então **troque pelo seu** — o valor
versionado é de outra conta. Se o bundle ID `br.usp.pcs3732.SmartLock` já estiver
tomado, use um sufixo único.

## Instalar no iPhone

No aparelho, uma vez só:

1. **Ajustes → Privacidade e Segurança → Modo de Desenvolvedor** → ativar → reiniciar.
2. Após a primeira instalação: **Ajustes → Geral → VPN e Gerenciamento de
   Dispositivo → App de Desenvolvedor** → confiar no certificado.

Pelo Xcode: selecione o iPhone como destino e `⌘R`. Por linha de comando:

```sh
xcrun devicectl list devices                       # pegue o identificador do aparelho
xcodebuild -project SmartLock.xcodeproj -scheme SmartLock \
  -destination 'id=<IDENTIFICADOR>' -allowProvisioningUpdates build
xcrun devicectl device install app --device <IDENTIFICADOR> \
  build/Debug-iphoneos/SmartLock.app
xcrun devicectl device process launch --device <IDENTIFICADOR> --console \
  br.usp.pcs3732.SmartLock
```

### Build de device sem o platform instalado

Enquanto o download de 8,5 GB não termina, o build de device falha em
`CompileAssetCatalogVariant` com `No available simulator runtimes for platform
iphonesimulator` — o `actool` exige um runtime de simulador mesmo compilando para
device. Como o catálogo só tem um AppIcon vazio e a cor de destaque, dá para
construir sem ele: mova `SmartLock/Assets.xcassets` para fora do projeto e rode

```sh
xcodebuild -project SmartLock.xcodeproj -target SmartLock -sdk iphoneos \
  -configuration Debug -allowProvisioningUpdates \
  ASSETCATALOG_COMPILER_APPICON_NAME= ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME= \
  ASSETCATALOG_COMPILER_GENERATE_SWIFT_ASSET_SYMBOL_EXTENSIONS=NO build
```

O app instala e roda normalmente, só sem ícone e com o azul padrão no lugar da
cor de destaque. Restaure o catálogo depois.

## Onde cada transporte é usado

- **Simulador**: `MockLockTransport`, uma Raspberry simulada. Dá para percorrer o
  fluxo inteiro (cadastro, aprovação, negação, timeout, desbloqueio, revogação)
  sem hardware. Os controles ficam na seção **Simulação** da tela inicial.
- **iPhone físico**: `BLELockTransport`, que procura periféricos anunciando o
  serviço `A1B20001-…`. Sem a Raspberry no ar a lista fica vazia — é o esperado.

O Simulador não tem rádio BLE; a escolha é feita em `AppEnvironment.makeTransport()`.

## Estrutura

```
SmartLock/
├── Protocol/     contrato compartilhado com Raspberry e Android (UUIDs, mensagens)
├── Bluetooth/    LockTransport + implementação Core Bluetooth + mock
├── Crypto/       HMAC-SHA256 do desafio–resposta
├── Keychain/     persistência da credencial
├── Domain/       máquina de estados, modelos e erros
└── Views/        SwiftUI
```

## Contrato do protocolo

Isto tem de bater byte a byte com a Raspberry e com o Android. Fonte de verdade:
`Protocol/LockProtocol.swift`.

### UUIDs

| Papel | UUID |
| --- | --- |
| Smart Lock Service | `A1B20001-5F6D-4C3E-9A2B-7E8F0D1C2B3A` |
| Device Information | `A1B20002-…` — read |
| Access Request | `A1B20003-…` — write with response |
| Approval Status | `A1B20004-…` — notify |
| Authentication Challenge | `A1B20005-…` — read |
| Authentication Response | `A1B20006-…` — write with response |
| Unlock Command | `A1B20007-…` — write with response |
| Operation Result | `A1B20008-…` — notify |

Todos os sufixos são `-5F6D-4C3E-9A2B-7E8F0D1C2B3A`.

### Mensagens

JSON UTF-8; campos binários em Base64. Toda mensagem carrega `"v": 1`; a outra
ponta rejeita versão diferente.

```jsonc
// → Access Request
{"v":1,"deviceId":"<uuid>","deviceName":"iPhone do Pedro"}

// ← Approval Status (notify). `secret` só em approved; 32 bytes.
{"v":1,"state":"pending|approved|denied|timeout","deviceId":"<uuid>",
 "secret":"<base64>","lockName":"Fechadura da Sala"}

// ← Device Information (read)
{"v":1,"lockId":"lock-01","lockName":"Fechadura da Sala","firmware":"1.0"}

// ← Authentication Challenge (read; cada leitura gera um nonce novo)
{"v":1,"nonce":"<base64 16 bytes>","ttl":5}

// → Authentication Response
{"v":1,"deviceId":"<uuid>","mac":"<base64 32 bytes>"}

// → Unlock Command
{"v":1,"deviceId":"<uuid>"}

// ← Operation Result (notify)
{"v":1,"op":"auth|unlock|enroll","status":"ok|denied|error|rate_limited","reason":null}
```

Cabe no MTU padrão do BLE; o app recusa escrever payload maior que o MTU negociado.

### Prova criptográfica

```
mac = HMAC-SHA256(secret, "unlock" || 0x00 || deviceId || 0x00 || nonce)
```

O contexto `"unlock"` amarra a prova ao comando: um MAC capturado não serve para
outra operação. Em Python:

```python
msg = b"unlock\x00" + device_id.encode() + b"\x00" + nonce
mac = hmac.new(secret, msg, hashlib.sha256).digest()
```

### Sequência do desbloqueio

1. Central lê `Authentication Challenge` → nonce + ttl.
2. Central escreve `Authentication Response` com o MAC.
3. Periférico notifica `Operation Result` com `op: "auth"`.
4. Se `ok`, central escreve `Unlock Command`.
5. Periférico aciona o atuador e notifica `Operation Result` com `op: "unlock"`.

O que a Raspberry precisa garantir: o nonce expira em `ttl`, cada nonce só vale
uma vez, a autenticação vale para um único comando, e tentativas inválidas
seguidas levam a `rate_limited`. O `MockLockTransport` implementa exatamente
essas regras — serve como especificação executável para o firmware.

## O que ainda não está feito

- Restauração de estado do Core Bluetooth (`CBCentralManagerOptionRestoreIdentifierKey`)
  e execução em segundo plano. Nesta versão o desbloqueio exige o app aberto,
  como previsto no plano.
- Reconexão automática ao voltar para o primeiro plano.
- Emparelhamento BLE com criptografia de link (LE Secure Connections); hoje a
  segurança vem só da camada de aplicação.
- Testes automatizados — o alvo de testes ainda não existe no projeto.

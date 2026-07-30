# Mensagens

JSON UTF-8 compacto (sem espaços) dentro das características GATT. Campos
binários viajam em Base64 — é como o `JSONEncoder` do Swift codifica `Data` por
padrão, então o formato foi escolhido para não exigir nada de especial no app.

Toda mensagem carrega `"v": 1`. Versão diferente é rejeitada pelas duas pontas:
o celular lança `unsupportedProtocol`, a Raspberry responde `status: "error"`.

## Do celular para a Raspberry

```jsonc
// Access Request
{"v":1,"deviceId":"<uuid>","deviceName":"iPhone do Pedro"}

// Authentication Response
{"v":1,"deviceId":"<uuid>","mac":"<base64, 32 bytes>"}

// Unlock Command
{"v":1,"deviceId":"<uuid>"}
```

`deviceId` é gerado pelo celular, um por fechadura — assim o mesmo aparelho não
fica correlacionável entre instalações diferentes. Recadastrar na mesma fechadura
reaproveita o identificador, e a Raspberry **substitui** o registro antigo em vez
de acumular linhas órfãs.

## Da Raspberry para o celular

```jsonc
// Device Information (read)
{"v":1,"lockId":"lock-01","lockName":"Fechadura da Sala","firmware":"1.0"}

// Approval Status (notify). `secret` só vem em approved; 32 bytes.
{"v":1,"state":"pending|approved|denied|timeout","deviceId":"<uuid>",
 "secret":"<base64>","lockName":"Fechadura da Sala"}

// Authentication Challenge (read; cada leitura gera um nonce novo)
{"v":1,"nonce":"<base64, 16 bytes>","ttl":5}

// Operation Result (notify)
{"v":1,"op":"auth|unlock|enroll","status":"ok|denied|error|rate_limited","reason":null}
```

`reason` é texto livre para diagnóstico e o app o exibe quando o status não é
`ok`. Não deve conter segredo nem nonce.

## Tamanho e MTU

A maior mensagem é o `Approval Status` aprovado: ~140 bytes com o segredo em
Base64. Não cabe no MTU ATT padrão de 23 bytes.

Na prática o iOS negocia MTU de 185 bytes logo após conectar, então sobra espaço.
Duas consequências:

- O app recusa escrever payload maior que o MTU negociado
  (`maximumWriteValueLength`) em vez de deixar o BlueZ truncar em silêncio.
- Uma central que não negocie MTU maior receberá o `Approval Status` truncado. O
  Android começa nos 23 bytes do ATT padrão, então o app pede `requestMtu(185)`
  logo após conectar. Se algum aparelho recusar o pedido, o caminho é fragmentar
  a mensagem — não encurtar o segredo de forma alguma.

## Notificações são difundidas

O BlueZ não endereça notificação a uma central específica: o `PropertiesChanged`
vai para todas as que deram `StartNotify`. Por isso toda mensagem notificada
carrega o `deviceId`, e o app descarta o que não é dele:

```swift
guard status.deviceId == request.deviceId else { continue }
```

Isso resolve a correção, não o sigilo — ver `security.md`.

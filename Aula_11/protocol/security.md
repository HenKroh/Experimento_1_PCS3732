# Segurança

## Prova criptográfica

```text
mac = HMAC-SHA256(secret, "unlock" || 0x00 || deviceId || 0x00 || nonce)
```

O contexto `"unlock"` amarra a prova ao comando: um MAC capturado não serve para
outra operação. Os separadores `0x00` evitam que `deviceId` e `nonce` de
tamanhos diferentes produzam a mesma mensagem concatenada.

Python:

```python
msg = b"unlock\x00" + device_id.encode() + b"\x00" + nonce
mac = hmac.new(secret, msg, hashlib.sha256).digest()
```

Swift: `LockCrypto.response` em `src/ios/SmartLock/Crypto/LockCrypto.swift`.

A comparação na Raspberry é em tempo constante (`hmac.compare_digest`).

## O que já está em pé

- Pareamento aprovado por botão físico — nenhum cadastro acontece sem alguém na
  frente do aparelho.
- Segredo de 32 bytes distinto por celular, de `os.urandom`.
- Armazenamento no Keychain (iOS) e no SQLite da Raspberry.
- Desafio–resposta com nonce; o segredo nunca trafega depois do cadastro.
- Nonce de uso único, com expiração em 5 s.
- Autenticação válida para um único comando.
- Bloqueio temporário após 3 tentativas inválidas, que sobrevive à reconexão.
- Revogação por celular (`python -m smartlock revoke <deviceId>`).
- Timeout de 55 s para a solicitação de cadastro.
- Log de acessos em SQLite, sem segredo nem nonce.

## Limitações conhecidas

### O segredo é difundido no momento do cadastro

O BlueZ não permite endereçar uma notificação a uma central específica. No
instante em que o proprietário aprova, o `Approval Status` — que contém o
segredo — vai para **todas** as centrais inscritas naquele momento.

Na prática a janela é estreita: exige um atacante já conectado e inscrito
enquanto o proprietário aperta o botão. Ainda assim é uma exposição real. As
saídas, em ordem de preferência:

1. **`--require-encryption`** (`SMARTLOCK_REQUIRE_ENCRYPTION=1`) marca as
   características com `encrypt-read`/`encrypt-write`/`encrypt-notify`. O BlueZ
   passa a exigir emparelhamento LE com link criptografado, e o tráfego deixa de
   ser legível para quem só está por perto. Vem desligado por padrão porque o
   pareamento LE atrapalha os primeiros testes de bancada.
2. Trocar o envio direto do segredo por criptografia assimétrica: o celular
   gera o par de chaves, manda só a pública no `Access Request`, e a Raspberry
   nunca precisa transmitir segredo nenhum. É a evolução prevista na seção 7 do
   plano e resolve o problema na raiz.

### Segredo em claro no SQLite

O HMAC é simétrico: a Raspberry precisa do mesmo segredo para recalcular a
prova, então não dá para guardar apenas um hash. O arquivo é criado com
permissão `0600`. Quem tiver root na Raspberry tem as credenciais — o que
criptografia assimétrica também resolveria.

### Sem autenticação da fechadura

O celular não verifica que está falando com a fechadura certa; um periférico
falso pode anunciar o mesmo UUID e coletar `Access Request`. Não coleta segredo
(quem o emite é a fechadura), mas consegue negar serviço e enganar o usuário.
Assinar o `Device Information` com uma chave da fechadura resolveria.

### Bloqueio por conexão, não por dispositivo

O contador de tentativas inválidas é indexado pelo endereço BLE da central. Um
atacante que randomize o endereço a cada tentativa contorna o bloqueio. Indexar
também por `deviceId` ajuda pouco (o atacante escolhe o campo); o limite real
tem de ser global — uma taxa máxima de tentativas por minuto na fechadura
inteira, ainda não implementada.

## Regra de log

Nunca registrar `secret`, `mac` ou `nonce`. O log guarda operação, status,
motivo, `deviceId` e o endereço da central — o bastante para auditar sem
transformar o arquivo de log numa segunda cópia das credenciais.

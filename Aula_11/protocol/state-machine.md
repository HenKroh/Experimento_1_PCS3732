# Máquina de estados

As três implementações precisam concordar nestes estados. A referência
executável é `src/raspberry/tests/test_service.py` do lado do periférico e o
`MockLockTransport` do lado do app.

## Conexão

```text
desconectado ──connect──▶ conectando ──▶ conectado ──▶ (assina notify) ──▶ lê Device Information
      ▲                                     │
      └──────── desconexão / erro ──────────┘
```

O app assina `Approval Status` e `Operation Result` **antes** de qualquer
escrita: a Raspberry pode notificar o resultado imediatamente depois de receber
o comando, e uma notificação perdida travaria o fluxo.

## Cadastro

```text
celular                        Raspberry                     proprietário
───────                        ─────────                     ────────────
escreve Access Request  ──▶    enfileira, acende LED
                        ◀──    notify state=pending
                                                             aperta Permitir
                               gera segredo de 32 bytes
                               grava em devices
                        ◀──    notify state=approved+secret
guarda no Keychain

                                                             aperta Negar
                        ◀──    notify state=denied

                               (55 s sem decisão)
                        ◀──    notify state=timeout
```

Regras:

- **Uma fila, não um slot.** Dois celulares pedindo ao mesmo tempo ficam em
  ordem de chegada; os botões sempre decidem sobre o mais antigo. Sem isso o
  proprietário não sabe o que está aprovando.
- Pedido repetido do mesmo celular substitui o anterior na fila.
- Celular que desconecta antes da decisão sai da fila.
- O timeout da Raspberry (55 s) é menor que o do app (60 s), para o app receber
  `timeout` explícito em vez de estourar o próprio relógio.
- `state=pending` é informativo; só `approved`, `denied` ou `timeout` encerram a
  espera do app.

## Desbloqueio

```text
celular                          Raspberry
───────                          ─────────
lê Authentication Challenge ──▶  gera nonce, expira em ttl
                            ◀──  {nonce, ttl}
calcula o MAC
escreve Auth Response       ──▶  confere
                            ◀──  notify op=auth, status
escreve Unlock Command      ──▶  gasta a sessão, aciona o atuador
                            ◀──  notify op=unlock, status
```

Regras do periférico:

- Cada leitura de `Authentication Challenge` gera um nonce novo e **derruba** o
  desafio e a autenticação anteriores daquela conexão.
- O nonce expira em `ttl` (5 s) e vale uma única vez.
- A autenticação bem-sucedida abre uma janela de `ttl` válida para **um** único
  `Unlock Command`, e só para o `deviceId` que se autenticou.
- O estado de desafio e sessão é **por conexão** (o endereço BLE da central),
  então dois celulares em paralelo não interferem um no outro.
- Tentativas inválidas seguidas (3) levam a `rate_limited` por 30 s. O contador
  sobrevive à reconexão — senão bastaria reconectar para zerá-lo.

### Uma diferença deliberada em relação ao mock do iOS

No `MockLockTransport`, `readChallenge` já lança `rateLimited` quando o
dispositivo está bloqueado. Na Raspberry o desafio é emitido normalmente e o
bloqueio aparece no `Operation Result` da autenticação.

O motivo é de transporte: uma leitura GATT que falha vira um erro ATT genérico,
que o app mostraria como falha de transporte em vez de "bloqueado
temporariamente". Emitir o nonce não custa nada — ele só serve para quem tem o
segredo. O estado final que o usuário vê é o mesmo.

## Atuador

`unlock` liga o relé e agenda o retravamento para `unlock_duration` (5 s). Um
segundo desbloqueio dentro da janela **reinicia** a contagem em vez de abrir uma
segunda janela, de modo que o tempo de abertura continua limitado a partir do
último comando válido. O serviço também retranca ao encerrar: um processo que
morre não deixa a porta aberta.

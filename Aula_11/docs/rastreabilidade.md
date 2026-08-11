# Matriz de rastreabilidade — requisitos × casos de teste

SmartLock — PCS3732, Grupo D. Complementa a seção 8 de [`relatorio.md`](relatorio.md).

Legenda de cobertura:

- **A** — teste automatizado (executa sem hardware, em CI ou na máquina de desenvolvimento)
- **S** — verificação manual no simulador iOS, contra o `MockLockTransport`
- **B** — ensaio de bancada, com a Raspberry e a Freenove Projects Board montadas

---

## 1. Requisitos funcionais

| Requisito | Cobertura | Casos de teste |
| --- | --- | --- |
| **RF-01** Anúncio BLE descobrível | A, B | `AdapterIndexTest.test_extrai_o_indice_do_nome`, `test_nome_sem_numero_cai_no_zero`, `BtmgmtFallbackTest.test_comando_leva_uuid_indice_e_flags`; **TB-01** |
| **RF-02** Sete características GATT | B | **TB-01** (inspeção com nRF Connect ou `bluetoothctl`) |
| **RF-03** Listagem por RSSI e conexão | S, B | Simulador: duas fechaduras anunciadas com RSSI −48 e −81, ordenadas na lista; **TB-01** |
| **RF-04** Solicitação de acesso | A, S, B | `LockCodecTest.Access Request carrega a versão do protocolo`; `LockManagerTest.cadastro aprovado guarda a credencial`; `ServiceTest.test_celular_autorizado_desbloqueia` (auxiliar `enroll`); **TB-02** |
| **RF-05** Cadastro exige botão físico | A, B | `ServiceTest.test_celular_autorizado_desbloqueia`, `ServiceTest.test_botao_negar_nao_cadastra`; **TB-02** |
| **RF-06** Segredo de 32 bytes por celular | A | `ServiceTest.test_celular_autorizado_desbloqueia`; `LockManagerTest.segredo de tamanho errado é recusado`; `LockCodecTest.decodifica Approval Status aprovado com o segredo em Base64` |
| **RF-07** Botão Negar não cadastra | A, S, B | `ServiceTest.test_botao_negar_nao_cadastra`; `LockManagerTest.botão Negar deixa o app sem credencial`; **TB-03** |
| **RF-08** Expiração da solicitação (55 s) | A, S, B | `LockManagerTest.sem resposta a tempo vira timeout`; **TB-04** |
| **RF-09** Sinalização por LED e notificação de estado | A (parcial), B | `ServiceTest` verifica as notificações de estado; o LED é observado em **TB-02**, **TB-03**, **TB-05** |
| **RF-10** Desafio–resposta HMAC-SHA256 | A | `LockCryptoTest.response reproduz o vetor gerado pela Raspberry`, `contexto diferente produz prova diferente`, `separadores impedem colisão entre deviceId e nonce`, `nonce diferente produz prova diferente`, `comparação em tempo constante`, `randomBytes devolve o tamanho pedido e não se repete`, `deviceId é um UUID novo a cada chamada`, `segredo do tamanho errado ainda produz MAC de 32 bytes`; `ServiceTest.test_celular_autorizado_desbloqueia`, `test_celular_nao_autorizado_e_rejeitado`; `LockManagerTest.desbloqueio envia o MAC do nonce recebido` |
| **RF-11** Nonce com validade e uso único | A | `ServiceTest.test_nonce_expirado_e_rejeitado` (relógio virtual avança `ttl + 1 s`) |
| **RF-12** Uma autenticação, um comando | A | `ServiceTest.test_autenticacao_vale_para_um_unico_comando` |
| **RF-13** Bloqueio após 3 tentativas inválidas | A (lado do app), B | `LockManagerTest.bloqueio temporário aparece com o texto certo`; **TB-06**. *Sem teste automatizado do lado da Raspberry — ver seção 3.* |
| **RF-14** Acionamento e retravamento automático | A (parcial), B | `ServiceTest.test_celular_autorizado_desbloqueia` verifica o acionamento; o retravamento **não** é coberto (o teste fixa `unlock_duration = 3600 s`); **TB-05** |
| **RF-15** Encerrar deixa a fechadura travada | B | **TB-08** |
| **RF-16** Persistência entre reinícios | A, B | `ServiceTest.test_reinicio_preserva_dispositivos_autorizados`; **TB-08** |
| **RF-17** Comandos administrativos | B | Execução manual de `devices`, `revoke` e `log` durante **TB-07** |
| **RF-18** Dispositivo revogado não abre | A, S, B | `ServiceTest.test_dispositivo_revogado_nao_desbloqueia`; simulador: botão *Revogar* da seção Simulação; **TB-07** |
| **RF-19** Recadastro substitui a credencial | A | `LockManagerTest.recadastro reaproveita o deviceId`; `LockManagerTest.remover a credencial exige novo cadastro` |
| **RF-20** Credencial no repositório seguro | A (parcial), B | `LockManagerTest.credenciais salvas são recarregadas na criação`, `remover a credencial exige novo cadastro`; o uso efetivo de Keychain/Keystore só é observável em aparelho — **TB-02** |
| **RF-21** Versionamento do protocolo | A | `LockCodecTest.versão diferente é recusada`, `campo desconhecido não quebra a leitura`, `payload inválido vira MalformedMessageException`, `decodifica Device Information`, `decodifica o desafio`, `decodifica Operation Result com status rate_limited`, `Authentication Response manda o MAC em Base64`, `Unlock Command leva apenas o deviceId`, `decodifica Approval Status negado sem segredo` |
| **RF-22** Registro de acessos sem segredo | A (parcial) | `ServiceTest` grava em `access_log` a cada operação; a ausência de segredo no log é garantida por inspeção de código — **sem teste automatizado dedicado** |

## 2. Requisitos não funcionais

| Requisito | Cobertura | Casos de teste / evidência |
| --- | --- | --- |
| **RNF-01** Confidencialidade da chave | — | Inspeção de código; nenhum teste automatizado. Lacuna registrada na seção 3 |
| **RNF-02** Resistência a repetição | A | `ServiceTest.test_nonce_expirado_e_rejeitado`, `test_autenticacao_vale_para_um_unico_comando` |
| **RNF-03** Comparação em tempo constante | A | `LockCryptoTest.comparação em tempo constante` |
| **RNF-04** Separação de domínio criptográfico | A | `LockCryptoTest.contexto diferente produz prova diferente`, `separadores impedem colisão entre deviceId e nonce` |
| **RNF-05** Entropia | A | `LockCryptoTest.randomBytes devolve o tamanho pedido e não se repete`, `deviceId é um UUID novo a cada chamada` |
| **RNF-06** Permissão do banco (`0600`) | — | Verificação manual (`ls -l smartlock.db`) durante **TB-08** |
| **RNF-07** Latência percebida | B | **TB-05** — cronometrar do toque ao acionamento |
| **RNF-08** Tolerância à falha do anúncio | A | `AdvertisementErrorTest.test_falha_do_dbus_aciona_o_contorno_e_o_loop_segue`, `test_as_duas_falhas_encerram_o_servico`, `BtmgmtFallbackTest.test_sem_confirmacao_do_mgmt_e_falha`, `test_maquina_sem_btmgmt_nao_estoura`, `test_remocao_so_acontece_se_houve_instancia` |
| **RNF-09** Segurança em falha | B | **TB-08** |
| **RNF-10** Ausência de condição de corrida | A (indireta), B | A suíte da Raspberry usa `FakeScheduler`, que serializa as chamadas; concorrência real em **TB-09** |
| **RNF-11** Testabilidade sem hardware | A | A própria existência da suíte: 15 casos que não importam `dbus`, `gi` nem `RPi.GPIO` |
| **RNF-12** Portabilidade de execução | A, B | Execução de `--no-gpio` em máquina sem hardware; `test_maquina_sem_btmgmt_nao_estoura` |
| **RNF-13** Configurabilidade | — | Inspeção de `config.py`; verificação manual ao alterar `SMARTLOCK_*` em **TB-05** |
| **RNF-14** Interoperabilidade entre plataformas | A | `LockCryptoTest.response reproduz o vetor gerado pela Raspberry` — vetor produzido por `src/raspberry/smartlock/authentication.py` |
| **RNF-15** Compatibilidade de MTU | A, B | `LockCodecTest.mensagens do celular cabem no MTU negociado`, `Approval Status aprovado não cabe no MTU padrão`, `Approval Status truncado não passa por válido`; **TB-12** |
| **RNF-16** Manutenibilidade | — | Avaliação estrutural (seção 5.2 do relatório) |
| **RNF-17** Observabilidade | B | Leitura de `journalctl -u smartlock` e de `python3 -m smartlock log` em **TB-05** e **TB-07** |
| **RNF-18** Usabilidade | S, B | Percurso completo no simulador; **TB-02**, **TB-05**, **TB-10** |

## 3. Lacunas de cobertura reconhecidas

Registradas explicitamente para que a ausência não seja lida como aprovação:

| Requisito | Lacuna | Encaminhamento |
| --- | --- | --- |
| RF-13 | O bloqueio por tentativas inválidas não é testado do lado da Raspberry, apenas do lado do app | Adicionar caso a `test_service.py`: três respostas com MAC inválido → `rate_limited`; avançar o relógio virtual em 30 s → volta a aceitar |
| RF-14 | O retravamento automático não é coberto: o teste fixa `unlock_duration = 3600 s` porque o `LockController` usa `threading.Timer` real | Injetar o `Scheduler` no `LockController`, como já é feito nos demais módulos, e então testar com relógio virtual |
| RF-08 | O timeout de cadastro é testado no app, mas não na Raspberry | Adicionar caso avançando o relógio virtual além de `enrollment_timeout` |
| RF-22, RNF-01 | Ausência de segredo no log garantida só por inspeção | Adicionar asserção que varra o `access_log` e o log de texto procurando o segredo emitido |
| RF-02, RF-15, RF-17 | Sem cobertura automatizada | Dependem de BLE, de encerramento do processo e de I/O de terminal; permanecem como ensaio de bancada |
| Todo o iOS | Nenhum teste automatizado — o alvo não existe no projeto Xcode | Criar `SmartLockTests` e portar os casos do `LockManagerTest` do Android, usando `MockLockTransport` e `InMemoryCredentialStore` |
| Fila com dois celulares | `EnrollmentQueue` não é testada com mais de um pedido pendente | Adicionar caso cobrindo `resolve_head`, substituição de pedido do mesmo peer e `forget_peer` |

## 4. Como reproduzir os testes automatizados

```sh
# Raspberry Pi — 15 casos, sem BLE e sem GPIO
cd src/raspberry
python3 -m unittest discover -s tests -t . -v

# Android — 37 casos na JVM, sem aparelho
cd src/android
./gradlew test
# relatório em app/build/reports/tests/testDebugUnitTest/index.html
```

Evidências da última execução em [`evidencias/`](evidencias/).

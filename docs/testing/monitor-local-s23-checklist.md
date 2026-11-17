# Monitor local — validação assistida no S23

## Estado

**Sessão assistida encerrada com evidência parcial.** Após solicitar conexão e
autorização para atualizar preservando dados, o usuário respondeu:
“Conectado, sim autorizo”. Verificações sintéticas e revisão final aprovadas;
TalkBack confirmado por relato no percurso dirigido da Visão geral. Cenários
físicos não observados permanecem explicitamente pendentes abaixo.

Modelo/API anteriormente observados: SM-S911B, Android 17/API 37.
Confirmá-los novamente somente após consentimento; não registrar serial.
Nenhum resultado esperado abaixo está validado apenas por constar neste arquivo.

## Regras de segurança

- Pedir autorização explícita para conexão/consultas e, separadamente, atualização.
- Selecionar exatamente o alvo autorizado, com `-s SERIAL` em todo comando.
- Nunca desinstalar, limpar dados, puxar banco privado ou executar instrumentados
  contra a instalação do usuário.
- Não abrir o C6 por automação nem solicitar senha, extrato ou comprovante.
- Não exigir Pix, pagamento ou passagem para gerar dados; comparar eventos naturais.
- Acesso especial à captura e permissão de avisos são decisões manuais distintas.
- Não conceder permissões por ADB nem mudar bateria, DND, volume ou canal sem ação
  e consentimento do usuário.
- Reinício, revogação de acesso e force-stop são ensaios separados, não automáticos.
- Não consultar `dumpsys notification`, Logcat de conteúdo ou exports privados
  para preencher lacunas. Registrar resultados sanitizados, sem nomes/CPF/texto bruto.
- Não iniciar a fase remota ou conectar aparelhos de apoio.

## Portão de instalação

- [x] Tarefa 8 e parte técnica da T9 concluídas, testes e revisão final aprovados.
- [x] Usuário autorizou nova identificação USB e atualização preservando dados.
- [x] Exatamente um alvo autorizado selecionado; modelo/API conferidos sem serial.
- [x] Pacote C6 confirmado pelo usuário; `BuildConfig.C6_PACKAGE_NAME` exato.
- [x] APK contém `br.com.c6tag.diagnostico`, versionCode 2/versionName 1.1.
- [x] Manifesto/APK auditados: sem INTERNET, alarme exato, DND ou full-screen.
- [x] SHA-256 e data do APK final registrados abaixo, antes da instalação.
- [x] Atualização por `adb -s SERIAL install -r ...` sem desinstalar/limpar dados.
- [x] Histórico de observações anterior disponível após atualização, conforme relato do usuário.

SHA-256 do APK:
`1eaacb831de78a2fb60d628e5bcffeb0dd72fd808a8aedad1bd3f6fe5ccad1bf`.
Data/build/commit: 2026-11-17, versão 1.1/código 2, checkpoint `2cfc926`
(código funcional `e2cb3c8`), 12.973.580 bytes.
Consentimento: recebido nesta sessão. `install -r --user 0` retornou `Success`;
versionCode 2 confirmado por metadados. Somente o Monitor foi iniciado,
com `am start -W --user 0`, `Status: ok`.
Usuário confirmou os registros anteriores acessíveis, a estimativa configurada,
débitos naturais reconhecidos e avisos permitidos/configurados manualmente.
Esses relatos não comprovam captura integral, som ou entrega física de alertas.

Metadados reconfirmados: um S23 SM-S911B, Android 17/API 37, usuário principal 0.
Origem `com.c6bank.app` instalada; app existente `br.com.c6tag.diagnostico`
com código 1. Nenhum serial foi incluído neste documento.
Uma consulta inicial sem filtro de usuário produziu aviso de acesso negado a
perfil restrito; consultas posteriores usam explicitamente o usuário principal 0.
Nenhuma tentativa de contornar o isolamento ou inspecionar esse perfil foi feita.

Exemplo para calcular o hash local, sem tocar no aparelho:

```powershell
Get-FileHash .\app\build\outputs\apk\debug\app-debug.apk -Algorithm SHA256
```

Os comandos de instalação documentados no README não são autorização para executá-los.

## Cenários funcionais

| Cenário | Ação consentida | Esperado | Observado | Status |
|---|---|---|---|---|
| Migração | Abrir o app atualizado e consultar diagnóstico existente. | Registros antigos preservados; sem lançamentos financeiros retroativos. | Usuário confirmou que os registros antigos aparecem em Ajustes → Diagnóstico técnico. Não houve extração ou contagem independente dos dados. | PARCIAL |
| Primeira referência | Usuário consulta saldo no C6 e informa saldo/limite com confirmação. | Referência substitui estimativa; passado não reaplicado, sem “recarga”. | Usuário informou que configurou o saldo ao abrir o app e confirmou que a estimativa aparece corretamente. Não foi solicitado o valor. | RELATADO |
| Avisos concorrentes | Se chegarem naturalmente durante a conferência, revisar após confirmar. | Candidatos continuam identificáveis/pendentes, sem inclusão silenciosa. | Não observado. | PENDENTE |
| Captura e avisos | Conferir cada acesso separadamente, com escolhas do usuário. | Autorização/conexão/capacidade distintas; recusa não bloqueia saldo/histórico. | Usuário relatou débitos naturais reconhecidos e confirmou separadamente que os avisos estão permitidos, com permissão e configuração feitas manualmente. Recusa, som e entrega de alerta não observados. | PARCIAL |
| Débito natural | Comparar um aviso novo que ocorrer naturalmente. | Uma aplicação elegível ou pendência explícita; nunca supor identidade bancária perfeita. | Usuário relatou débitos reconhecidos de notificações recebidas naturalmente após configurar a referência. Sem conteúdo ou valores coletados e sem auditoria aritmética independente. | PARCIAL |
| Crédito natural | Comparar um crédito recebido por iniciativa externa ao teste. | Uma aplicação; crédito insuficiente não encerra ciclo baixo nem pausa vigente. | Não observado. | PENDENTE |
| Repetição natural | Se um aviso for atualizado/reapresentado naturalmente, comparar o histórico. | Sem novo impacto automático conhecido; ambiguidade vira pendência. | Não observado. | PENDENTE |
| Correção | Usuário confere saldo e confirma novo valor. | Novo marco, valor substituído, histórico mantido e ajuste sem receita. | Não observado. | PENDENTE |
| Pendência legível | Usuário confere evidência e escolhe “nova” ou “já incluída/não contar”. | Decisão persistida uma vez; toque repetido não duplica impacto. | Não observado. | PENDENTE |
| Pendência ilegível | Abrir um caso que ocorrer naturalmente, sem editar valor bancário. | Orientação de conferência; nenhum valor inventado ou exigência de credenciais. | Não observado. | PENDENTE |
| Tema e navegação | Alternar Sistema/Claro/Escuro, abas e diagnóstico; reabrir app. | Preferência local e organização preservadas, conteúdo acessível. | Não observado. | PENDENTE |
| Exportação | Se autorizado, cancelar uma tentativa e exportar outra para destino escolhido. | Exportação explícita/sensível; cancelamento não compartilha arquivo. | Não observado. | PENDENTE |
| Limpeza técnica | Somente se usuário autorizar apagar observações. | Saldo, histórico financeiro, decisões/pausa preservados; arquivos exportados não apagados. | Não observado. | PENDENTE |

## Avisos sem transação bancária

Se o usuário desejar, pode elevar **temporariamente o limite de atenção** acima
da estimativa atual e restaurá-lo depois. Não informar saldo falso nem alterar o
limite automaticamente. O app deve continuar mostrando que o valor é estimado.

- [x] Usuário realizou o ensaio proposto e confirmou a restauração do limite original. Não foi informado se houve anotação prévia do limite.
- [x] Primeiro aviso local relatado pelo usuário: “Funcionou, ele notificou corretamente”. Som, latência e condição de tela não informados.
- [x] Após ciência, usuário confirmou “Lembretes pausados”, fim previsto da pausa e saldo inalterado. Supressão temporal e fim automático da pausa ainda não observados.
- [x] Retomada manual: usuário confirmou que “Lembretes pausados” desapareceu e voltou “Saldo abaixo do limite”.
- [ ] Prazo da retomada e ausência de rajada de avisos observados ao longo do tempo.
- [x] Após restauração do limite original, usuário confirmou que “Saldo abaixo do limite” desapareceu. Evidência visual do encerramento da condição de teste, não de crédito bancário.
- [x] Limite original restaurado pelo usuário, conforme confirmação após orientação.

### Medição temporal

Não acelerar o agendador de produção nem esperar que TestDriver prove o Samsung.
Anotar cada ensaio individualmente; não generalizar pontualidade de um evento.

| Data | Condição consentida | Prazo previsto | Horário observado | Atraso / não observado | Limite da conclusão |
|---|---|---|---|---|---|
| A preencher | App em segundo plano | A preencher | Não observado | Pendente | Não prova entrega futura. |
| A preencher | Tela bloqueada | A preencher | Não observado | Pendente | Android/fabricante podem atrasar ou suprimir avisos. |
| A preencher | Fim da pausa | A preencher | Não observado | Pendente | Elegível na próxima avaliação, não alarme exato. |

## Ensaios independentes e acessibilidade

- [x] TalkBack no percurso dirigido da Visão geral: usuário confirmou funcionamento, incluindo botões, após orientação de leitura/foco e abas.
- [ ] Checagem física ampliada de fonte até 200%, foco/leitura e alvos em todas as telas e ambos os temas; cobertura sintética registrada separadamente.
- [ ] Landscape e retorno à Activity sem perder formulário ou confirmar sozinho.
- [ ] Canal silencioso/bloqueado ou avisos recusados, somente por escolha do usuário.
- [ ] Revogar/restaurar captura: indicador de conferência sem recuperação fictícia.
- [ ] Reiniciar e desbloquear: continuidade a conferir quando não comprovada.
- [ ] Force-stop separado: registrar que captura/lembretes podem parar até reabertura.

Nada acima deve ser marcado como concluído sem observação real e consentida.

## Encerramento

Resultado: **PARCIAL** — atualização e início do Monitor confirmados por comando;
usuário confirmou registros anteriores, referência configurada, estimativa correta,
débitos naturais reconhecidos e avisos permitidos/configurados manualmente.
Primeiro aviso local confirmado por relato; som e pontualidade não medidos.
Estado de pausa, prazo exibido e saldo inalterado após ciência confirmados por relato.
Retomada visual e saída da condição de saldo baixo ao restaurar o limite original
confirmadas por relato. Prazos, supressão temporal, recuperação por crédito natural
e demais cenários ainda pendentes. TalkBack aprovado por relato no percurso
dirigido da Visão geral, sem inferir auditoria de todas as telas/temas.

Usuário informou: “testei e esta funcional isso no talk back, botoes etc.
Tudo certo”. Nenhum valor lido, conteúdo bancário ou gravação foi solicitado.
Se ativado somente para o ensaio, o usuário pode desativar o TalkBack manualmente
nas configurações; a desativação não foi confirmada nesta sessão.

Registrar fatos estabelecidos, limitações e itens não observados no relatório do
monitor. Se um evento não ocorrer naturalmente, deixá-lo pendente; não criar
transações ou extrair dados privados para completar o checklist.

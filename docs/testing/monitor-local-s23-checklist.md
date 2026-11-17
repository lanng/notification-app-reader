# Monitor local — validação assistida no S23

## Estado

**Não executado.** A instalação diagnóstica anterior não autoriza esta atualização.
Este roteiro prepara a Tarefa 10; a Tarefa 9 deve concluir a verificação sintética
e a revisão final antes de propor instalação física.

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

- [ ] Tarefas 8/9 concluídas, testes completos e revisão final aprovados.
- [ ] Usuário autorizou nova identificação USB e atualização preservando dados.
- [ ] Exatamente um alvo autorizado selecionado; modelo/API conferidos sem serial.
- [ ] Pacote C6 confirmado pelo usuário; `BuildConfig.C6_PACKAGE_NAME` exato.
- [ ] APK contém `br.com.c6tag.diagnostico`, versionCode 2/versionName 1.1.
- [ ] Manifesto/APK auditados: sem INTERNET, alarme exato, DND ou full-screen.
- [ ] SHA-256 e data do APK final registrados abaixo, antes da instalação.
- [ ] Atualização por `adb -s SERIAL install -r ...` sem desinstalar/limpar dados.
- [ ] Histórico de observações anterior ainda disponível após atualização.

SHA-256 do APK: **a preencher após o build final**.
Data/build/commit: **a preencher**.
Consentimento e resultado da atualização: **pendentes**.

Exemplo para calcular o hash local, sem tocar no aparelho:

```powershell
Get-FileHash .\app\build\outputs\apk\debug\app-debug.apk -Algorithm SHA256
```

Os comandos de instalação documentados no README não são autorização para executá-los.

## Cenários funcionais

| Cenário | Ação consentida | Esperado | Observado | Status |
|---|---|---|---|---|
| Migração | Abrir o app atualizado e consultar diagnóstico existente. | Registros antigos preservados; sem lançamentos financeiros retroativos. | Não observado. | PENDENTE |
| Primeira referência | Usuário consulta saldo no C6 e informa saldo/limite com confirmação. | Referência substitui estimativa; passado não reaplicado, sem “recarga”. | Não observado. | PENDENTE |
| Avisos concorrentes | Se chegarem naturalmente durante a conferência, revisar após confirmar. | Candidatos continuam identificáveis/pendentes, sem inclusão silenciosa. | Não observado. | PENDENTE |
| Captura e avisos | Conferir cada acesso separadamente, com escolhas do usuário. | Autorização/conexão/capacidade distintas; recusa não bloqueia saldo/histórico. | Não observado. | PENDENTE |
| Débito natural | Comparar um aviso novo que ocorrer naturalmente. | Uma aplicação elegível ou pendência explícita; nunca supor identidade bancária perfeita. | Não observado. | PENDENTE |
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

- [ ] Usuário escolheu esse ensaio e anotou o limite original.
- [ ] Primeiro aviso local observado; distinguir solicitação aceita de som/visibilidade.
- [ ] Ciência pausou os lembretes, sem mudar saldo ou encerrar o ciclo.
- [ ] Retomada manual respeitou o ciclo/prazo atual, sem rajada de avisos.
- [ ] Recuperação confiável ou restauração consentida do limite encerrou o ciclo.
- [ ] Limite original restaurado pelo usuário.

### Medição temporal

Não acelerar o agendador de produção nem esperar que TestDriver prove o Samsung.
Anotar cada ensaio individualmente; não generalizar pontualidade de um evento.

| Data | Condição consentida | Prazo previsto | Horário observado | Atraso / não observado | Limite da conclusão |
|---|---|---|---|---|---|
| A preencher | App em segundo plano | A preencher | Não observado | Pendente | Não prova entrega futura. |
| A preencher | Tela bloqueada | A preencher | Não observado | Pendente | Android/fabricante podem atrasar ou suprimir avisos. |
| A preencher | Fim da pausa | A preencher | Não observado | Pendente | Elegível na próxima avaliação, não alarme exato. |

## Ensaios independentes e acessibilidade

- [ ] Fonte até 200%, TalkBack, foco/leitura e alvos de toque em ambos os temas.
- [ ] Landscape e retorno à Activity sem perder formulário ou confirmar sozinho.
- [ ] Canal silencioso/bloqueado ou avisos recusados, somente por escolha do usuário.
- [ ] Revogar/restaurar captura: indicador de conferência sem recuperação fictícia.
- [ ] Reiniciar e desbloquear: continuidade a conferir quando não comprovada.
- [ ] Force-stop separado: registrar que captura/lembretes podem parar até reabertura.

Nada acima deve ser marcado como concluído sem observação real e consentida.

## Encerramento

Resultado: **PENDENTE** — nenhuma validação do monitor no aparelho físico.

Registrar fatos estabelecidos, limitações e itens não observados no relatório do
monitor. Se um evento não ocorrer naturalmente, deixá-lo pendente; não criar
transações ou extrair dados privados para completar o checklist.

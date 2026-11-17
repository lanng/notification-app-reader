# Descoberta: monitor pessoal do C6 Tag

## Objetivo
Estudar um aplicativo Android pessoal que estime o saldo a partir de um saldo
informado e das notificações do C6 Bank, alertando quando atingir um limite.
Antes de implementar, validar as informações realmente disponíveis no celular.

## Etapas
- [x] Explorar o contexto do projeto: diretório inicialmente vazio, sem Git.
- [x] Avaliar necessidade de apoio visual: não necessário nesta etapa técnica, sem mockups.
- [x] Esclarecer requisitos e condições do aparelho para o coletor, uma pergunta por vez.
- [x] Comparar duas ou três abordagens e recomendar uma.
- [x] Apresentar o desenho e o teste de viabilidade para aprovação.
- [x] Documentar o desenho aprovado em docs/superpowers/specs/.
- [x] Revisar a especificação: revisão independente aprovada, sem bloqueios.
- [x] Obter revisão e aprovação do usuário sobre a especificação escrita.
- [x] Preparar plano de implementação após aprovação.

## Hipóteses a validar
- O Android disponibiliza o texto completo das notificações relevantes.
- Notificações individuais e resumos agrupados podem ser distinguidos.
- Atualizações de uma mesma notificação não provocam dupla contabilização.
- Os avisos de débito representam o movimento que se deseja contabilizar.
- Créditos e demais débitos relevantes são notificados ou ajustados manualmente.
- A estimativa precisa sinalizar incerteza: não equivale ao saldo oficial do banco.

## Restrições iniciais
- Sem API bancária, credenciais ou automação de acesso ao aplicativo do banco.
- Não implementar o aplicativo antes da aprovação do desenho.
- Nenhum repositório Git encontrado; não inicializar nem criar commits sem alinhar.

## Informações confirmadas pelo usuário
- Aparelho: Samsung Galaxy S23, Android 17.
- A conta é usada exclusivamente para C6 Tag: débitos de tags e depósitos para recarga.
- O usuário observa notificações de débito e crédito em tempo real.
- O saldo estimado deve somar créditos e subtrair débitos a partir do saldo informado.
- PC e conexão USB com depuração disponíveis para os testes.
- Segundo o usuário, o débito efetivo coincide com a chegada da notificação,
  mesmo quando a utilização da tag ocorreu dias antes. Essa será a hipótese operacional.

## Decisão aprovada
- Começar com coletor de diagnóstico nativo em Kotlin e evoluir gradualmente.
- Alternativas apresentadas: automação com ferramentas prontas ou app completo direto.
- A aprovação do caminho não substitui a aprovação do desenho do coletor.
- O usuário aprovou posteriormente o escopo do coletor de diagnóstico apresentado.

## Necessidade futura: aviso a responsáveis de apoio
- Um aparelho principal acessa a conta e captura as notificações do banco.
- Um ou dois aparelhos Android adicionais devem receber aviso de saldo baixo
  caso o responsável principal não confirme ciência.
- Os responsáveis de apoio poderão realizar recarga sem acessar a conta PF.
- Não faz parte do primeiro coletor: transporte, autenticação, pareamento,
  prazo de escalonamento e política de confirmação ainda precisam de desenho.
- Diferenciar ciência, compromisso de recarga e confirmação de crédito.
- Sem conexão do aparelho principal, não há como transmitir novos movimentos:
  ausência de atualização deve ser tratada separadamente de saldo baixo.
- O coletor inicial continua local e sem permissão de internet.

## Planejamento autorizado
- Usuário aprovou a especificação escrita e autorizou prosseguir ao plano.
- Ambiente WSL sem Java/Gradle/ADB no PATH; Android Studio e SDK Windows presentes.
- SDKs locais incluem API 33 a 36; JDK 21 Windows disponível.
- Não instalar ferramentas nem inicializar Git como efeito colateral do planejamento.
- Plano aprovado em revisão independente; usuário escolheu execução por agentes.
- Pesquisa oficial incorporada; listener seguirá exported=false conforme exemplo
  oficial, corrigindo recomendação anterior de exported=true.

## Execução
- Aparelho permanecerá desconectado por escolha do usuário. Solicitar conexão e
  autorização USB somente quando forem necessárias para a validação no S23.
- Tarefa 1 implementada e aprovada nas revisões de escopo e qualidade.
  Verificação independente executou assembleDebug/lintDebug/testDebugUnitTest:
  BUILD SUCCESSFUL; testes JVM ainda NO-SOURCE. APK-base gerado.
- Compose BOM ajustado para 2026.04.01 por compatibilidade com API 36/AGP 9.0.1.
- Tarefa 2 implementada, revisões de escopo e qualidade aprovadas.
  Verificação independente:
  build/lint/testDebugUnitTest com exit 0; XML confirma 19 testes, sem falhas,
  erros ou ignorados. Ainda não há coleta no aparelho.
- Tarefa 3 implementada, revisão de escopo pediu reforço de cobertura: comprovar
  mesma chave + mesmo valor interpretado em duas gravações independentes. Sem
  defeito de deduplicação observado. Testes e documentação reforçados; nova revisão
  de escopo aprovada, revisão de qualidade aprovada, com 29 testes JVM e 6
  instrumentados reportados. Build, compilação de
  testes instrumentados, lint e suíte JVM reconfirmados com exit 0. O implementador
  reportou execução SQLite no emulador e está complementando os comandos/evidências
  na documentação; nenhuma interação com aparelho físico.
- Tarefa 4 implementada, revisões de escopo e qualidade aprovadas. Verificação independente
  de build/JVM/compilação instrumentada/lint com exit 0. XMLs conferidos: 32 JVM
  e 16 instrumentados, sem falhas, erros ou ignorados. Binding e conteúdo real
  do C6 ainda não validados; alvo padrão continua vazio.
- Tarefa 5 implementada, revisão de escopo pediu correção: falha de abertura das
  configurações substituía indevidamente o estado conhecido de autorização.
  Corrigido com RED comportamental e testes de regressão; nova revisão de escopo
  aprovada, revisão de qualidade aprovada. Implementador reportou 32 JVM e
  35 instrumentados verdes, também conferidos pelo revisor.
  Controller reconfirmou
  build/JVM/APK de teste/lint: exit 0. XMLs conferidos: 32 JVM e 32 instrumentados
  sem falhas, erros ou ignorados. Tela, histórico, detalhes e confirmação de
  limpeza implementados; exportação segue para a Tarefa 6.
- Tarefa 6 implementada; revisão de escopo pediu cobertura de cancelamento com
  stream já aberto e nova execução forçada de lint para evidência atual.
  Nenhum bug de execução observado; teste acrescentado sem alteração de produção,
  lint forçado concluído, nova revisão de escopo e revisão de qualidade
  aprovadas. Rodada ampliada:
  32 JVM e 47 instrumentados reportados. Controller reconfirmou
  build/JVM/APK de teste/lint com exit 0 e XMLs de 32 JVM e 46 instrumentados
  sem falhas/erros/ignorados. Exportação manual e regras de backup empacotadas;
  captura real e binding do listener no S23 continuam pendentes.
- [x] Tarefa 1: scaffold, ferramentas e build; revisão de escopo e qualidade.
- [x] Tarefa 2: modelos e classificação; TDD e duas revisões.
- [x] Tarefa 3: histórico SQLite e recorder; TDD e duas revisões.
- [x] Tarefa 4: listener e snapshot; testes e duas revisões.
- [x] Tarefa 5: interface e estados; testes e duas revisões.
- [x] Tarefa 6: exportação e backup; testes e duas revisões.
- [ ] Tarefa 7: teste assistido no S23, dependendo do aparelho disponível.
- [x] Tarefa 8a: verificação local e revisão final do código, resultados e limites.
- [ ] Tarefa 8b: consolidar os cenários físicos restantes e discutir próxima fase.
- Etapas 1 a 6 aprovadas. Próxima etapa depende de conexão e autorização USB
  pelo usuário; confirmar versão/API e pacote real do C6 antes de compilar alvo
  e instalar. Não conceder acesso de listener automaticamente.
- Preparação da Tarefa 7 criada (roteiro, README e fallback de configuração
  local); revisão de escopo aprovada e qualidade pediu restringir origem de
  configuração a -P explícito (não herdar propriedades globais/ambiente Gradle),
  conforme contrato. Correção com fixture isolada e orientação de consentimento
  no README concluídas; nova revisão de escopo aprovada e revisão de qualidade
  aprovada. Preparação concluída; teste físico pendente. Build/JVM/APK de teste/lint reconfirmados
  com exit 0. Nenhum local.properties criado/alterado; alvo final vazio.
- Usuário confirmou conexão USB. Consulta ADB direcionada identificou exatamente
  um aparelho autorizado: SM-S911B, Android 17, API 37, candidato com.c6bank.app.
  Sem instalação, acesso de listener ou leitura de conteúdo. Aguardando confirmação
  do alvo e autorização para instalar o APK.
- Usuário confirmou alvo e autorizou instalação. Criado local.properties com
  c6.packageName=com.c6bank.app; build/JVM/APKtest/lint sem erros. BuildConfig
  verificado antes do adb install -r, que retornou Success no S23 confirmado.
  Aberto apenas o diagnóstico. Aguardando concessão manual de acesso especial.
- Após orientação de acesso manual, usuário relatou várias notificações na lista.
  Primeira evidência de captura real no S23; próxima conferência: tipo, classificação
  e valor de um débito, sem solicitar dados pessoais ou transações de teste.
- Usuário conferiu 8 notificações referentes a tags e 2 propagandas do C6.
  Propagandas não geraram valor. Devem ser ignoradas na futura contabilização;
  no coletor diagnóstico permanecem como não reconhecidas, sem descarte de evidência.
  Recebimento de evento novo com app em segundo plano ainda precisa de teste natural.
- Usuário propôs voluntariamente Pix de R$ 1,00. Após orientação de deixar o
  diagnóstico em segundo plano e aguardar o aviso, confirmou reconhecimento
  correto do crédito e atualização da lista. Sem operação automatizada, leitura
  de comprovante ou dados pessoais. Débito novo e demais cenários seguem pendentes.
- Revisão final do código do coletor aprovada, sem bloqueios. Resumos de README e
  evidências atualizados para refletir a instalação/captura já observadas. Não iniciar
  saldo nem alertas automaticamente: cenários físicos restantes continuam pendentes.

### Observações de revisão não bloqueantes
- O wrapper ADB faz a checagem preventiva de múltiplos dispositivos no servidor
  padrão, mesmo se opções avançadas -H/-P/-L forem fornecidas. Confirmado no
  código, tools/adb.ps1:65. Não afeta o teste local planejado; não usar servidores
  ADB alternativos neste experimento sem corrigir/verificar esse encaminhamento.
- Tarefa 2: revisão sugere testes diretos adicionais para construtor com valor
  negativo e enums de conexão; invariantes já implementadas, sem bloqueios.
- Emulador Windows existente identificado: Medium_Phone_API_36.1. Avaliar execução
  temporária/read-only para testes instrumentados sem usar nem alterar o S23.
- Tarefa 3: revisão de qualidade aprovada. Melhorias opcionais: rejeitar tipos
  lifecycle na entrada recordNotification (hoje os chamadores devem usar caminho
  correto) e acrescentar teste direto de JSON corrompido. Não observados defeitos
  bloqueantes; callbacks da Tarefa 4 devem respeitar os tipos permitidos.
- Tarefa 5: após novo registro, a lista retorna à primeira página; páginas antigas
  devem ser carregadas novamente. Política simples aceita nesta etapa diagnóstica,
  sem perda dos registros no banco. Revisão de qualidade aprovada sem bloqueios.

## Próxima evidência
- Obter exemplos expandidos de débito e crédito, com dados pessoais ocultados.
- A presença de avisos em tempo real não confirma, por si só, entrega sem falhas,
  ausência de duplicações ou correspondência entre aviso e lançamento definitivo.

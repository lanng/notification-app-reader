# Coletor de diagnóstico C6 Tag

Status: desenho e documento aprovados pelo usuário; revisão independente aprovada;
planejamento de implementação autorizado.

## 1. Objetivo e decisão

Validar em um Samsung Galaxy S23 com Android 17 se os eventos de notificação
do C6 Bank fornecem informação suficiente para um futuro monitor pessoal de
saldo estimado. A primeira entrega é um APK de diagnóstico, não um controle
financeiro pronto para uso operacional.

Tecnologia escolhida para esta etapa: Kotlin nativo, Jetpack Compose e
armazenamento privado local. O banco de dados e as versões das dependências
serão definidos no plano de implementação.

O usuário dispõe de PC, USB e depuração USB para instalação e diagnóstico.
Não há projeto existente nem repositório Git inicializado neste diretório.

## 2. Contexto e hipóteses

- A conta PF é usada exclusivamente para tags e depósitos destinados às tags.
- Apenas o responsável principal tem acesso à conta no aplicativo do banco.
- Os exemplos mostram crédito com título “Você recebeu um Pix” e texto
  “Pix recebido no valor de R$ 2,00”; débito com título “Débito C6 Tag” e
  texto “Valor a debitar R$ 16,91”.
- As notificações podem estar separadas ou agrupadas.
- Segundo a observação do usuário, a cobrança efetiva ocorre quando chega a
  notificação, embora o uso da tag possa ser de vários dias antes.
- Essa correspondência será uma hipótese a validar com consulta manual ao banco,
  não uma garantia do aplicativo.
- A data de uso não deve ordenar lançamentos retroativos no futuro saldo.
- Horário de captura pelo coletor não prova o horário de lançamento bancário:
  uma reconexão pode revelar notificações antigas.

## 3. Escopo da primeira entrega

### Incluído

- Orientação para conceder o acesso especial às notificações no Android.
- Estado da autorização e da conexão do listener, sem prometer monitoramento
  contínuo apenas porque a autorização está concedida.
- Filtro pelo identificador real do aplicativo C6 instalado, verificado no
  aparelho; não depender apenas do nome ou do título da notificação.
- Histórico persistente dos eventos do C6 e dos eventos de conexão do coletor.
- Registro de recebimento/atualização, remoção e fotografia das notificações
  ativas quando o listener conecta.
- Lista e detalhe dos registros, incluindo origem, horário, identificador,
  agrupamento, título e texto disponível.
- Classificação experimental como candidato a crédito Pix, débito C6 Tag,
  resumo de grupo ou evento não reconhecido.
- Extração de valor quando o texto corresponder a um formato conhecido.
- Limpeza manual dos registros e exportação local iniciada pelo usuário.

### Excluído

- Saldo calculado, saldo inicial, limiar e alerta sonoro operacional.
- Recargas automáticas, login bancário, API bancária e leitura de extratos.
- Recuperação garantida de notificações perdidas ou já removidas.
- Comunicação entre aparelhos, servidor, push ou acesso à internet.
- Garantia de entrega integral de notificações pelo banco ou pelo Android.
- Infraestrutura genérica para funcionalidades futuras.

## 4. Organização e fluxo

O módulo de captura concentra a integração com NotificationListenerService:
recebe callbacks, verifica o aplicativo de origem e produz registros locais.
O acesso especial é amplo no Android, mas notificações de outros aplicativos
não serão persistidas nem exibidas em logs.

O módulo de diagnóstico armazena os registros e classifica o conteúdo conhecido.
A interface de classificação recebe dados de notificação e retorna uma
interpretação, sem produzir movimentações financeiras nem alterar saldo.

O módulo de apresentação permite consultar o histórico, o estado de captura,
apagar dados e solicitar exportação.

Fluxo: Android → filtro C6 → persistência local do evento → classificação
experimental → visualização.

Separar a classificação da captura permite testar exemplos sem depender de
eventos reais do banco. Não criar transporte remoto ou abstrações de transporte
na primeira versão.

## 5. Dados e interpretação

Cada registro deve preservar, quando disponíveis:

- Identificador local sequencial e tipo de evento.
- Instante de observação pelo coletor.
- Horário de postagem informado pelo Android, separado do horário observado.
- Identificador de pacote, chave da notificação, ID e tag.
- Chave de grupo e indicação de resumo de grupo.
- Título, texto comum, texto expandido e linhas textuais presentes nos campos
  relevantes da notificação, preservados separadamente.
- Classificação, valor extraído e motivo de não reconhecimento, quando aplicável.

Não coletar indiscriminadamente extras, imagens, intents ou outros objetos da
notificação. Valores reconhecidos devem ser representados em centavos inteiros,
sem ponto flutuante.

Cada callback recebido deve resultar em um registro próprio. Não eliminar
silenciosamente repetições: observar atualizações é parte do experimento.
Uma chave já observada pode indicar atualização, mas não é prova suficiente
de identidade de uma transação bancária.

Resumos de grupo não serão tratados como cobranças. A fotografia inicial
das notificações ativas terá origem explícita e não será apresentada como
evidência de novas transações. Não há contabilização financeira nesta fase.

## 6. Falhas e limites

- Se faltar autorização, mostrar o estado e orientar a concessão.
- Se o listener desconectar, registrar o evento quando possível e mostrar o
  último estado conhecido. Não prometer detecção imediata de toda interrupção.
- Se o texto estiver ausente, ocultado ou desconhecido, preservar o evento
  disponível e marcar como não reconhecido; não inferir um valor.
- Se ocorrer falha ao persistir, sinalizar a falha de diagnóstico; não afirmar
  que o evento foi salvo.
- Remover uma notificação não representa estorno.
- Mudança de texto, agrupamento, restrições de bateria e reinicialização são
  condições a observar no aparelho, não comportamentos presumidos.
- Eventos perdidos durante interrupções podem ser irrecuperáveis. Não mostrar
  um estado de “captura completa” sem evidência.

## 7. Privacidade

- Sem permissão de internet na primeira versão.
- Dados apenas no armazenamento privado do aplicativo; excluir registros de
  diagnóstico de backup automático e transferência de dados do Android.
- Não expor o texto bancário em logs de produção ou no Logcat por padrão.
- O histórico pode conter nomes, CPF parcialmente mascarado e locais.
- A tela de exportação deve avisar que o arquivo contém dados pessoais e
  permitir cancelar; exportar somente após ação explícita.
- O usuário deverá revisar/ocultar dados antes de compartilhar. Não prometer
  anonimização automática nesta versão.
- A limpeza remove os registros internos, não arquivos já exportados.

## 8. Plano de validação e critérios de saída

Testes automatizados deverão cobrir classificação e extração com exemplos
sanitizados de débito e Pix, formatos monetários brasileiros, texto ausente,
resumo de grupo e formato desconhecido. O histórico deve preservar múltiplos
eventos de uma mesma chave e sobreviver a reabertura do app.

No aparelho, registrar uma matriz de evidências:

1. Autorização, conexão e confirmação do pacote real do C6.
2. Crédito real e débito natural, comparados manualmente com o banco.
3. Notificação individual e agrupada; expansão/recolhimento e remoção.
4. Duas movimentações distintas de mesmo valor, quando houver oportunidade.
5. Tela bloqueada e aplicativo fora de primeiro plano.
6. Revogação/restauração do acesso, reconexão e reinicialização.
7. Funcionamento sem internet do coletor, limpeza e exportação.

Não exigir transações desnecessárias; testes sem evento real disponível ficam
explicitamente pendentes. Eventos sintéticos auxiliam testes, mas não substituem
evidência de comportamento do C6.

Critério para planejar o monitor de saldo: explicar os eventos observados,
identificar candidatos a movimentações, resumos e atualizações, e documentar
ambiguidades e limitações de recuperação. Se não for possível distinguir
movimentos com confiança, não liberar contabilização automática.

## 9. Evolução registrada, fora desta implementação

### Monitor local de saldo

Saldo inicial manual + créditos − débitos, histórico, correção/reconciliação,
limiar configurável e alerta sonoro. Requer desenho próprio, inclusive marco
inicial de contabilização e tratamento de períodos sem captura.

### Avisos a responsáveis de apoio

Necessidade informada: se o responsável principal não confirmar ciência de
um alerta de saldo baixo, avisar um ou dois aparelhos Android adicionais,
para que seus responsáveis possam fazer a recarga.

Proposta a estudar posteriormente: sincronização mínima do estado de alerta
com um coordenador remoto que possa executar o prazo de escalonamento sem
depender de o aparelho principal continuar conectado após registrar o alerta.
Não há escolha de fornecedor nem aprovação de uma arquitetura remota nesta etapa.

Restrições a preservar:

- Nenhuma credencial bancária deve ser compartilhada.
- Não é necessário compartilhar o texto bruto de notificações bancárias.
- Ciência, compromisso de recarregar e crédito confirmado são estados distintos.
- Sem conexão do aparelho principal, novos movimentos não podem ser transmitidos.
- Ausência de atualização não comprova saldo baixo: demanda sinalização própria.
- Entrega de push, som e ação humana não podem ser garantidos.

Pendências dessa etapa: pareamento, autenticação, prazo, confirmação,
persistência, repetição de avisos, expiração da ciência, resolução por crédito,
prevenção de recargas duplicadas, privacidade e monitoramento de conectividade.

## 10. Aprovação e próximos passos

O usuário aprovou o escopo do coletor em conversa e adicionou a necessidade
futura de apoio remoto. Revisar este documento antes de criar o plano de
implementação. Não inicializar Git nem criar commits sem alinhamento:
o diretório atual não é um repositório.

A revisão independente não encontrou bloqueios para o planejamento. Reforçou
manter testes com exemplos sanitizados e separar callbacks da fotografia inicial
de notificações ativas, conforme já previsto neste desenho.

# Tarefa 2 — modelos e classificador determinístico

## Evidências TDD

Comando usado no Windows, via script do projeto:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:testDebugUnitTest --console=plain
```

- **RED inicial (antes dos modelos):** compilação de `compileDebugUnitTestKotlin`
  falhou porque `NotificationClassifier`, `CandidateKind` e os modelos ainda
  não existiam. Isso foi uma falha de compilação; não foi contado como execução
  de testes.
- **RED comportamental:** após introduzir os modelos e um classificador mínimo
  que sempre retornava `UNKNOWN`, o comando compilou e executou **19 testes;
  8 falharam** por asserções, incluindo débito, Pix, resumo, `bigText`, linhas,
  duplicatas de campos e valores monetários.
- **GREEN:** depois da implementação, o mesmo comando concluiu com
  `BUILD SUCCESSFUL`. O XML de resultados registra **19 testes, 0 falhas,
  0 erros e 0 ignorados** em
  `app/build/test-results/testDebugUnitTest/TEST-br.com.c6tag.diagnostico.diagnostic.NotificationClassifierTest.xml`.

## Cobertura

Testes verificam as frases e títulos conhecidos, prioridade de resumo,
conteúdo ausente/oculto e desconhecido, `bigText` completo com `text` truncado,
conflito entre campos, candidatos repetidos no mesmo campo, repetição idêntica
entre campos e `lines`; parsing BRL em centavos (zero, centavos, milhares,
`Long.MAX_VALUE`, overflow e formatos inválidos); invariantes do resultado; e
preservação de origem, horários e remoções sem inferir estorno ou identidade
financeira.

## Verificação final

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain
```

Resultado mais recente: `BUILD SUCCESSFUL` (53 tarefas, 1 executada, 52
atualizadas).
Não foram executados testes instrumentados.

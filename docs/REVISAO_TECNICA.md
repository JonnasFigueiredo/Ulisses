# Revisão técnica do Ulisses

Revisão concluída em 23/10/2026 sobre a cópia local de `JonnasFigueiredo/Ulisses`.

## Avaliação

O Ulisses tem material para um portfólio acima de um CRUD: inferência ONNX em Java, captura guiada, autenticação, persistência e reaproveitamento do fluxo facial em um caso de uso. A separação por domínio e os testes de decisão são bons pontos de partida.

A principal distância para uma apresentação profissional estava na diferença entre o que a documentação prometia e o que o código comprovava, além de configurações inseguras e lacunas de teste. Esta revisão corrige um primeiro conjunto concreto. **O resultado está preparado para testes locais; publicação aberta ainda depende dos bloqueadores descritos abaixo.**

## Escopo e limites

Foram revisados backend, frontend, projetos nativos, configurações, Docker, scripts de execução, CI, documentação e testes. As conclusões combinam leitura estática, testes automatizados, build e consulta aos contratos dos fornecedores. Não é uma auditoria de segurança certificada nem um benchmark biométrico.

Já havia alterações locais em `ChallengeService`, `FaceProperties`, `FaceService`, `Detection`, configuração, testes de face, `camera.js`, `VerifyPage.jsx` e scripts de execução. Elas foram preservadas; correções desta revisão foram acrescentadas sobre o estado existente. O diff contra o último commit inclui trabalho anterior à revisão.

Não foram criados commits nem publicados artefatos remotos. Credenciais, `.env`, banco existente e templates de usuários não foram modificados.

## Problemas corrigidos

| Prioridade | Problema encontrado | Correção e evidência |
|---|---|---|
| P1 | Compose ativava `prod` com segredos públicos de fallback | Removidos os fallbacks; `.env.example` sem segredos reutilizáveis; validação de produção rejeita valores vazios, curtos e exemplos conhecidos; testes de contexto e configurações inválidas |
| P1 | PAD usava classe viva 2 e entrada dividida por 255 | Corrigido para classe 1 e BGR float32 0–255, conforme fornecedor; testes do contrato de entrada e da configuração |
| P1 | Fases extras e ordem de movimentos não eram validadas antes da inferência | Cadastro/verificação rejeitam rótulos indevidos e ordem regressiva; testes de fases extras e TURN2 antes de TURN1 |
| P1 | Pose oposta ou neutro apenas ao final podiam satisfazer a condição de início do movimento | Exige desvio absoluto neutro observado antes dos frames do movimento; regressões para os dois casos |
| P1 | Imagens pequenas em bytes podiam declarar dimensões enormes e esgotar memória na decodificação | Novo `FrameDecoder` inspeciona cabeçalho antes de alocar pixels; máximo de 4096 por lado e 4 milhões de pixels; regressão com PNG de cabeçalho adulterado |
| P1 | Dependências nativas tinham alerta crítico conhecido | Capacitor atualizado para 8.4.3 em npm e Swift Package Manager; lockfile atualizado |
| P2 | Node 20 no CI/Docker era incompatível com Capacitor 8 | Alinhados em Node 22; `engines` e `.nvmrc` documentam o requisito |
| P2 | Backend CI executava `./mvnw` embora o arquivo esteja registrado sem bit executável | CI usa `sh mvnw -B verify`; instruções Unix também usam o interpretador explicitamente |
| P2 | JSON malformado, multipart incompleto e método incorreto viravam 500 | Tratamento das exceções MVC mantém 400/405/413/415, cabeçalhos e formato `{error}`; testes MockMvc |
| P2 | Senhas aceitas pelo DTO podiam exceder o limite do BCrypt | Verificação de 72 bytes UTF-8 antes do encoder, incluindo login; testes com ASCII e caracteres multibyte |
| P2 | Normalização de username dependia do locale e validava tamanho antes de remover espaços | `Locale.ROOT` e validação do comprimento normalizado; teste com locale turco |
| P2 | Uma falha temporária em `/auth/me` apagava a sessão | Token preservado em falhas de rede/503; interface permite repetir; 401 real encerra apenas a sessão correspondente |
| P2 | Cliente HTTP podia aguardar indefinidamente ou aceitar HTML como sucesso | Timeout, `AbortSignal`, validação de resposta e erros explícitos; testes de transporte e leitura do corpo |
| P2 | Troca de servidor podia reutilizar o JWT anterior | URL validada e token removido ao mudar de backend; teste de isolamento da sessão |
| P2 | Esperar parado no centro consumia os 16 frames e encerrava o desafio antes do prazo | Mantém somente os últimos frames neutros antes do movimento e reinicia trajetórias longas dentro do prazo; três testes com câmera simulada |
| P2 | Vite 5 e dependências transitivas tinham alertas de segurança | Vite 6.4.5 e patches compatíveis; retirada de `@capacitor/assets`, ferramenta opcional fora do build, cuja árvore antiga concentrava alertas sem correção compatível |
| P2 | README atribuía taxas de fraude e conformidade sem benchmark | Reescrito com limites explícitos, decisões, comandos consistentes e evidências disponíveis; removidas probabilidades e taxas não medidas |

As portas Docker agora ficam em loopback por padrão. Isso reduz exposição involuntária; HTTPS e proteção do acesso continuam necessários para hospedagem.

## Pendências priorizadas

### P1 — Proteger endpoints públicos e limitar a carga antes de abrir a demonstração

**Locais:** `backend/src/main/java/com/ulisses/auth/AuthController.java:25`, `auth/AuthService.java:27`, `face/FaceService.java:138` e `face/FaceService.java:598`.

Login e criação de contas não têm controle de frequência. Cadastro facial também pode executar inferência repetidamente. Os limites existentes são por usuário/instância; `size()` seguido de `add()` não é atômico, e a limpeza de buckets vazios não elimina buckets inativos com timestamps antigos. Cadastros públicos permitem criar novas contas para contornar limites por usuário.

**Impacto:** tentativas de senha e contas em massa, consumo de CPU/memória, crescimento de dados e indisponibilidade de uma instância pequena.

**Próximo trabalho:** limitar IP/conta/rota e concorrência global, impor timeout e fila limitada para inferência, retornar 429 com `Retry-After` e testar concorrência. Para a primeira publicação, manter a demonstração com acesso restrito até que esses controles existam; não expor o backend diretamente fora do proxy.

### P1 — Validar a eficácia real e a identidade durante cada fase

**Locais:** `face/FaceService.java:157`, `face/FaceService.java:377`, `face/FaceService.java:638` e `backend/src/test/java/com/ulisses/face/PadEvaluationTest.java`.

A consistência do cadastro usa a média de todas as comparações. Com sete vetores de uma pessoa e três vetores ortogonais de outra, a média é `24/45 = 0,533`, acima do limiar 0,45. Se a pessoa minoritária ocupa uma fase inteira, pode gerar um template próprio. A identidade dos giros também é agregada entre segmentos. Essa conclusão é uma análise do algoritmo, não uma medição de ataque com pessoas reais.

**Próximo trabalho:** comparar cada fase e cada segmento com a identidade frontal, testar mistura de identidades e medir o efeito em recusas de usuários legítimos. O cadastro atual é livre pelo próprio titular; essa política também precisa ser apresentada claramente.

A correção de pré-processamento do PAD não valida o limiar 0,55. O harness ignora imagens sem detecção e retorna taxa zero para conjunto vazio, podendo imprimir resultado artificialmente favorável. Deve exigir amostras válidas nas duas classes, contabilizar falhas de aquisição, separar calibração de validação e registrar modelos, hardware, condições e tamanho da amostra. Não usar o resultado atual para anunciar FMR, APCER ou conformidade.

### P1 — Definir retenção e transparência para uma demonstração pública

**Locais:** `face/FaceController.java`, `face/FaceTemplateRepository.java`, `face/VerificationAttemptRepository.java`, páginas de cadastro e `SECURITY.md`.

Há exclusão dos templates, mas não existe um fluxo completo de exclusão da conta e dos históricos nem prazo de retenção implementado. Apagar biometria não equivale a apagar todos os dados da conta. Antes de convidar visitantes, apresentar o que será enviado ao servidor, o que permanece armazenado e como excluir; definir política de limpeza e implementá-la.

Este item é uma recomendação de engenharia e produto, não uma declaração de conformidade jurídica.

### P2 — Corrigir concorrência e calendário do ponto

**Locais:** `timeclock/TimeClockService.java:28`, `:35`, `:43`, `:58`, `:66` e `TimeClockRepository.java:10`.

Duas requisições com desafios válidos podem ler o mesmo último registro e gravar duas entradas do mesmo tipo. `@Transactional` sozinho não serializa a decisão por usuário. O dia usa o fuso do servidor; um container em UTC difere do horário brasileiro. O comentário promete primeira marcação diária como entrada, mas a consulta considera todo o histórico; é preciso decidir como tratar turnos que cruzam a meia-noite.

**Próximo trabalho:** regra de jornada explícita, `Clock` e `ZoneId` configuráveis, exclusão mútua no banco/idempotência, ordenação determinística e testes de concorrência/virada do dia. Mantenha a funcionalidade apresentada como demonstração até isso ser resolvido.

### P2 — Concluir cancelamento e estados de erro da câmera

**Locais:** `frontend/src/useFaceCapture.js:22`, `frontend/src/camera.js:278`, `frontend/src/pages/EnrollPage.jsx`.

O cliente HTTP agora aceita cancelamento, mas os loops de captura e a tela de cadastro ainda não o propagam integralmente ao sair da rota. `useLivePosition` transforma falhas de API em pose ausente, podendo instruir a pessoa a centralizar o rosto quando o backend está indisponível. As etapas também não limitam cada chamada ao tempo restante da captura; o timeout HTTP geral é mais longo que o prazo da etapa.

**Próximo trabalho:** `AbortController` por captura, descarte de resultados antigos, timeout por etapa, diferenciação entre erro de serviço e pose inválida. Testar navegação durante captura, queda de rede, 429 e carregamento dos modelos.

### P2 — Concluir atualização do React Router

**Locais:** `frontend/package.json`, `frontend/package-lock.json`.

O audit final aponta dois pacotes moderados, `react-router` e `react-router-dom`, na mesma cadeia. Os avisos incluem redirecionamento por destino com barra invertida e hidratação SSR. Nesta SPA os destinos observados são fixos e não há SSR; isso reduz a superfície identificada, mas não elimina o alerta da dependência.

**Próximo trabalho:** migrar de forma testada para uma versão corrigida da linha 7, incluindo rotas protegidas, redirecionamentos e navegação em WebView. Não foi aplicado `npm audit fix --force` para ocultar uma migração sem teste de interface.

### P2 — Fixar e verificar os modelos baixados

**Locais:** `face/engine/ModelStore.java:26`, `face/engine/FaceEngine.java:56`, `application.yml`.

URLs usam referências mutáveis e o cache aceita arquivo existente pelo tamanho. Não há hash/versionamento que confirme qual modelo foi carregado. Um arquivo errado ou corrompido pode impedir a inferência e resultados não ficam reproduzíveis por versão.

**Próximo trabalho:** manifesto com versão, SHA-256 e contrato de entrada/saída; download temporário com limite e validação antes de promover; readiness que reflita carregamento/erro dos modelos. Fechar recursos nativos e streams mesmo em inicialização parcial com falha.

### P2 — Separar configuração mobile de desenvolvimento e distribuição

**Locais:** `frontend/capacitor.config.json:6`, `frontend/ios/App/App/Info.plist:60` e tela de login.

HTTP e conteúdo misto estão permitidos para conexão por IP. Se usados fora de laboratório, senha, JWT e frames trafegam sem TLS. O token fica no `localStorage`; uma execução de script na origem da aplicação pode acessá-lo.

**Próximo trabalho:** configuração de release com HTTPS, teste em dispositivos reais e política de sessão adequada ao cliente. Não distribuir o APK antigo como se já incluísse a atualização de Capacitor; é necessário recompilar.

### P2 — Evoluir persistência sem perder dados existentes

**Locais:** `application-prod.yml`, entidades, repositórios e `crypto/EmbeddingCipher.java`.

H2 em arquivo e `ddl-auto: update` são convenientes localmente, mas não dão migrações versionadas nem um procedimento de recuperação. Consultas por usuário/data merecem índices compostos. A chave de criptografia é estática no processo e o formato legado é reconhecido por um único byte; a rotação/migração deve ser explícita e testada.

**Próximo trabalho:** Postgres + Flyway, backup/restore testado, índices e restrições, estratégia de versão e rotação de chaves. Preservar a chave existente enquanto houver templates dependentes dela. Para um piloto restrito com H2, usar uma única instância e volume persistente com backup consistente.

### P2 — Tratar cadastro concorrente e completar testes de integração

**Locais:** `auth/AuthService.java:33`, `auth/User.java`, `auth/JwtAuthFilter.java` e testes.

A checagem de username livre e o `save` são operações separadas. Dois cadastros concorrentes podem produzir erro de integridade que ainda termina em 500. Os testes atuais não exercitam toda a combinação real de filtros, banco e transações.

**Próximo trabalho:** mapear especificamente o conflito de username para 409; testar isolamento entre usuários, JWT inválido/expirado, unicidade sob concorrência e persistência real. Introduzir testes de integração com banco descartável e E2E das rotas principais.

### P3 — Manutenção, apresentação e experiência de contribuição

- Dividir `FaceService` em políticas de cadastro/verificação, qualidade e rate limiting sem reescrever a arquitetura inteira.
- Extrair captura e políticas de pose do arquivo React, adicionar lint/formatação e considerar TypeScript incrementalmente nas fronteiras da API.
- Eliminar afirmações absolutas ainda presentes em comentários internos, como garantia de impedir replay; comentários devem descrever a checagem concreta.
- Adicionar API OpenAPI, logs estruturados sem dados biométricos, identificação de requisição e métricas de latência/erros.
- Configurar atualização automatizada e auditoria de dependências Maven; o `npm audit` não cobre Java, imagens Docker nem sistema operacional.
- CI: Android, testes em Windows para os scripts e artefatos/relatórios de teste. Fixar versões de runners/ferramentas quando necessário à reprodutibilidade.
- `run.cmd build` pode continuar após falha de uma etapa; caminhos Java específicos da máquina e a checagem de readiness dos scripts precisam ser portáveis.
- Acessibilidade: testar teclado, foco, leitores de tela, anúncio de erros/progresso e contraste. Adicionar seletores semânticos aos estados de captura.
- Gravar vídeo curto real e publicar um estudo de caso: problema, decisões, limitações, evidências e evolução. Não exibir métricas inventadas nem dados biométricos de terceiros.

## Validação executada

| Verificação | Resultado |
|---|---|
| Backend `mvn verify`, JDK 21 | 82 testes contabilizados: 81 passaram, 1 ignorado; zero falhas/erros; JAR gerado |
| Teste ignorado | `PadEvaluationTest`, porque não foi fornecido dataset |
| Frontend `npm test`, Node 24.16 local | 14 testes passaram |
| Frontend `npm run build` | Passou com Vite 6.4.5; JavaScript principal aproximadamente 64 kB gzip |
| Smoke HTTP do JAR com H2 em memória | Passou: rota protegida sem token = 401, criação de conta, consulta autenticada, JSON inválido = 400, desafio com duas direções e engine `READY` usando modelos locais; processo encerrado ao final |
| `npm audit` antes | 23 pacotes com alerta: 4 críticos, 13 altos, 6 moderados |
| `npm audit` depois | 2 moderados na cadeia React Router; nenhum alto/crítico |
| `git diff --check` | Sem erros de whitespace |
| Docker | `docker compose config --quiet` passou com valores sintéticos; daemon indisponível, portanto imagens e containers não foram executados nesta revisão |
| iOS/Android | Código/configuração revisados; builds nativos e testes de câmera não executados |
| Benchmark biométrico | Não executado; correções de contrato não representam medição de eficácia |

No ambiente local, Maven foi executado com o JDK 21 e a distribuição já instalada, apontando explicitamente o cache. O wrapper falhou dentro do ambiente restrito, e `java` no PATH apontava para 17 apesar do `JAVA_HOME` 21. O build validado não deve ser confundido com validação dos scripts em uma máquina limpa.

A instalação de Node 22 no CI foi corrigida na configuração, mas o CI remoto ainda precisa rodar após a publicação das mudanças.

## Ordem recomendada

1. **Você testa localmente:** seguir o roteiro manual e registrar falhas com navegador/dispositivo, etapa e mensagem.
2. **Preparar uma demonstração restrita:** resolver controle de acesso/carga, HTTPS, armazenamento persistente e limpeza de dados; executar smoke test no ambiente de hospedagem.
3. **Abrir a demonstração:** somente após os controles anteriores e validação do fluxo real. Exibir seu caráter experimental e evitar usos sensíveis.
4. **Fortalecer o portfólio:** vídeo, estudo de caso e métricas medidas; depois migrações, integração/E2E, observabilidade e revisão da arquitetura de captura.

## Referências técnicas consultadas

- [Contrato ONNX do fornecedor do MiniFASNet](https://github.com/yakhyo/face-anti-spoofing/blob/main/onnx_inference.py): entrada BGR sem divisão e classe viva 1.
- [Advisory oficial do Capacitor](https://github.com/ionic-team/capacitor/security/advisories/GHSA-rvm3-566m-v7fv): versões afetadas e patch 8.4.3; atualização exige rebuild dos aplicativos.
- [Requisitos do Capacitor 8](https://capacitorjs.com/docs/updating/8-0): Node 22+ e requisitos nativos.
- [Política de suporte do Vite](https://vite.dev/releases): a linha 6.4 ainda recebe correções de segurança na data da revisão.
- [Licenças dos modelos InsightFace](https://github.com/deepinsight/insightface/blob/master/python-package/docs/model_zoo.md): licença do código e dos pesos são diferentes.
- Alertas restantes: [GHSA-wrjc-x8rr-h8h6](https://github.com/advisories/GHSA-wrjc-x8rr-h8h6) e [GHSA-337j-9hxr-rhxg](https://github.com/advisories/GHSA-337j-9hxr-rhxg), identificados pelo `npm audit`.

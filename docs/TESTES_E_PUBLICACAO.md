# Testes locais e preparação para publicação

Este roteiro acompanha a revisão de 23/10/2026. Primeiro valide os fluxos na sua máquina; depois prepare a hospedagem. Uma demonstração com acesso restrito reduz a exposição enquanto as pendências da [revisão técnica](REVISAO_TECNICA.md) são resolvidas.

## 1. Preparar seu teste

1. Abra o Docker Desktop e confirme que o daemon está disponível com `docker info`.
2. Confira o `.env` na raiz. Se não existir, copie `.env.example` e preencha dois segredos aleatórios independentes, conforme o [README](../README.md).
3. Se já houver dados, preserve a chave de criptografia. As novas verificações rejeitam os antigos exemplos públicos; uma chave rejeitada com templates existentes exige migração ou uso de ambiente de teste novo, não troca silenciosa.
4. Execute `docker compose up --build -d` e acompanhe `docker compose logs -f backend`.
5. Espere `Engine de reconhecimento facial pronta`. Abra **http://localhost:5173**, permita a câmera e crie uma conta de teste.

Não use `docker compose down -v` para apenas parar a aplicação: esse comando também remove os volumes. Para encerrar, use `docker compose down`.

Alternativa sem Docker: JDK 21 em `JAVA_HOME`, Node 22+, backend com `.\mvnw.cmd spring-boot:run` e frontend com `npm ci` seguido de `npm run dev`. O modo nativo não lê automaticamente o `.env` do Compose.

O frontend usa HTTP no desenvolvimento normal e HTTPS no modo `npm run dev:mobile`. No navegador, câmera por IP remoto requer contexto seguro; `http://localhost` é uma exceção de desenvolvimento.

## 2. Critérios de aceitação manual

Marque apenas o que você executar. **Nenhum item desta lista está marcado como validado por câmera real nesta revisão.**

| Feito | Cenário | Resultado esperado |
|---|---|---|
| [ ] | Criar conta e sair/entrar novamente | Nome correto, sessão restaurada ao recarregar |
| [ ] | Senha incorreta e usuário já existente | Mensagem compreensível; sem travamento ou erro interno |
| [ ] | Abrir rota protegida sem sessão | Redireciona ao login |
| [ ] | Negar permissão da câmera | Explica como liberar a câmera; permite sair da tela |
| [ ] | Cadastro com boa luz, uma pessoa e três poses | Cria cadastro e atualiza o estado na página inicial |
| [ ] | Rosto distante, desfocado ou fora do oval | Orientação corresponde ao problema; cadastro inadequado não é aceito |
| [ ] | Outra pessoa aparece na imagem | Captura é rejeitada/orientada conforme o filtro de múltiplos rostos |
| [ ] | Verificar o próprio rosto | Executa centro → movimento 1 → centro → movimento 2 e mostra resultado |
| [ ] | Esperar no centro antes de começar o movimento | Não encerra apenas por consumir 16 observações neutras |
| [ ] | Fazer movimento errado ou permanecer parado | Não aprova; apresenta motivo e permite nova tentativa |
| [ ] | Repetir com esquerda/direita/cima/baixo | Direção mostrada corresponde ao movimento reconhecido |
| [ ] | Testar outro rosto em uma conta já cadastrada | Não aceitar como o titular; registrar qualquer aceitação inesperada |
| [ ] | Foto impressa e reprodução em tela, com consentimento | Registrar resultado; não presumir proteção porque um único ataque falhou |
| [ ] | Excluir a biometria | Estado vira não cadastrado e a verificação não reutiliza o cadastro removido |
| [ ] | Registrar entrada e saída | Mostra tipo, horário e histórico correspondentes |
| [ ] | Recarregar com backend temporariamente indisponível | Preserva token, apresenta erro e permite tentar novamente |
| [ ] | Sair da rota durante captura | Câmera desliga; anotar se há chamadas residuais ou mensagens tardias |
| [ ] | Sessão expirada | Retorna ao login sem ficar preso no fluxo de captura |
| [ ] | Tema claro/escuro, tela pequena e teclado | Campos/botões legíveis, foco visível, conteúdo sem cortes |
| [ ] | Reiniciar containers | Conta e cadastro continuam disponíveis com a mesma chave/volumes |

Há pendências conhecidas no cancelamento de captura, controle de carga, mistura de identidades e concorrência do ponto. Uma falha nesses cenários deve ser registrada e tratada; não reduza limiares de reconhecimento ou desligue PAD apenas para obter aprovação.

Para registrar uma falha, informe: sistema/navegador ou modelo do aparelho, tela, passos, resultado esperado, mensagem observada e horário. Se anexar logs, retire tokens, senhas, imagens e outros dados pessoais.

## 3. Verificações automatizadas antes de publicar

Na pasta `backend/`:

```powershell
.\mvnw.cmd verify
```

Na pasta `frontend/`:

```powershell
npm ci
npm test
npm run build
npm audit
```

Em Linux/macOS, use `sh mvnw verify`. Confirme também o CI remoto no commit que será publicado. Os testes de contrato/mocks não comprovam eficácia biométrica e não substituem os cenários manuais acima.

## 4. Bloqueadores para uma demonstração aberta

| Item | Condição de aceite |
|---|---|
| Controle de acesso e abuso | Login/cadastro/inferência com limites testados; cadastro público protegido; alternativa inicial é acesso restrito no proxy |
| Transporte | Domínio com HTTPS válido e redirecionamento de HTTP; câmera funciona no navegador remoto |
| Rede | Somente proxy exposto; API 8080 e banco inacessíveis diretamente pela internet |
| Segredos | Valores exclusivos no provedor/host; nenhum exemplo, segredo em Git ou chave compartilhada com dev |
| Persistência | Volume ou banco persistente e procedimento de backup/restore testado; chave de criptografia preservada |
| Recursos | Limites de memória/CPU, concorrência controlada, medição de latência e comportamento sob sobrecarga |
| Modelos | Carregamento concluído antes de liberar captura; cache persistente e verificação de artefatos planejada |
| Dados da demonstração | Explicação visível da coleta, prazo de retenção e exclusão implementada; sem dados reais de terceiros no seed |
| Monitoramento | Saúde do backend e da engine, logs sem segredos, identificação de falhas e procedimento de rollback |
| Dependências | Alertas restantes triados e migração do Router acompanhada; auditoria Java e imagens antes do deploy |

O projeto usa dados e processamento que não cabem apenas em uma hospedagem de arquivos estáticos: o backend precisa de Java 21, bibliotecas nativas do ONNX Runtime e persistência. Escolha o ambiente depois de medir memória e CPU com os modelos carregados. Não há dimensionamento nem custo de hospedagem validado nesta revisão.

## 5. Topologia inicial de hospedagem

```text
Navegador
    │ HTTPS
Proxy com TLS e controle de acesso/carga
    │ rede privada / loopback
Frontend Nginx ── /api ── Backend Java + modelos ONNX
                              │
                       banco / volume persistente
```

Para o primeiro piloto, use **uma instância do backend**: desafios e limitadores são mantidos em memória. Não habilite múltiplas réplicas sem tratar esse estado. Prefira servir frontend e `/api` sob a mesma origem para simplificar o acesso pelo navegador.

O Compose local publica apenas em `127.0.0.1`, adequado para um proxy no mesmo host. Se o proxy estiver em outro container, conecte-o à rede interna do Compose. A mudança de `ULISSES_BIND_ADDRESS` é uma decisão de rede; não abra todas as interfaces por conveniência sem definir o acesso externo.

Configure `SPRING_PROFILES_ACTIVE=prod` e `ULISSES_CORS_ORIGINS` com origens exatas, sem `*`. Segredo JWT e chave de templates precisam ser independentes. A validação de tamanho rejeita exemplos fracos conhecidos, mas não consegue provar a aleatoriedade de uma chave.

## 6. Sequência de publicação

1. Resolver os bloqueadores aplicáveis e concluir os testes locais.
2. Escolher host e domínio; preparar segredos, persistência, TLS e acesso restrito.
3. Gerar imagens a partir de um commit identificado e manter a versão anterior para rollback.
4. Subir em ambiente de teste, esperar a engine ficar pronta e verificar logs/saúde.
5. Executar cadastro, verificação, exclusão e reinicialização no domínio HTTPS real.
6. Confirmar limites de requisição e comportamento de erro sem carga destrutiva.
7. Validar backup/restauração com dados descartáveis.
8. Só então liberar os visitantes previstos e gravar o vídeo do portfólio.

Um banner de demonstração experimental ajuda a comunicar o propósito, mas não substitui os controles técnicos. Para uma apresentação inicial, um vídeo local e acesso por convite também são entregas úteis enquanto a abertura pública é preparada.

## 7. Mobile e rede local

Para testar o app nativo, recompile após a atualização do Capacitor: `npm run build`, `npx cap sync android` ou `npx cap sync ios`, seguido do build nativo. Um APK já existente não recebe a correção automaticamente.

Se precisar acessar Docker pelo IP do computador, configure `ULISSES_BIND_ADDRESS` com o IP da interface da rede local, reinicie o Compose e restrinja o firewall à rede de teste. A configuração HTTP nativa atual é de laboratório; prepare uma configuração de release com HTTPS antes de distribuir.

Nenhum deploy, configuração de provedor, publicação de DNS ou distribuição de APK foi executado nesta revisão.

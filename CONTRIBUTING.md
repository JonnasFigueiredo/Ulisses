# Contribuindo com o Ulisses

Obrigado pelo interesse! Este guia cobre o essencial para contribuir.

## Ambiente

- **Backend:** JDK 21. Rode os testes com `cd backend && ./mvnw test`.
- **Frontend:** Node 18+. `cd frontend && npm install && npm run dev`.
- **Tudo junto:** `docker compose up --build` (ver [README](README.md)).

## Fluxo

1. Abra uma issue descrevendo o problema/proposta antes de um PR grande.
2. Crie um branch a partir de `main`.
3. Garanta que os testes passam (`./mvnw test`) e o frontend builda (`npm run build`).
4. Descreva claramente a mudança no PR. O CI (GitHub Actions) roda testes e builds automaticamente.

## Padrões

- Mantenha o estilo do código existente (nomes, comentários explicando o "porquê", não o "o quê").
- Mudanças no pipeline facial ou na prova de vida devem vir acompanhadas de testes.
- **Segurança/biometria:** não faça afirmações de eficácia sem medição. Se mexer na liveness passiva, valide com o harness (`PadEvaluationTest`) e reporte APCER/BPCER.

## Escopo sensível

Este projeto processa **dado biométrico (sensível pela LGPD/GDPR)**. Contribuições que afetem armazenamento, retenção ou exposição desses dados exigem atenção redobrada — descreva o impacto de privacidade no PR.

# TupleTrace — frontend (React 19 + TypeScript + Vite + PWA)

```bash
npm install
cp .env.example .env.local   # VITE_USE_MOCKS=true : démo sans backend
npm run dev                  # proxy /api -> https://localhost:8443 (WildFly)
npm test && npm run build    # build dans dist/, à servir depuis WildFly
```

Règles : jeton en mémoire uniquement (jamais localStorage/sessionStorage) ; Service Worker limité
au shell statique, `/api/**` jamais mis en cache ; données simulées signalées par un bandeau.

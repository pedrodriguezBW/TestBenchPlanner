# Interfaz React de TestBenchPlanner

Sustituye al HTML de la 4.9.4 bloque a bloque (ver `docs/03-arquitectura.md`).
La lógica de negocio vive en el backend Java; aquí solo se pinta lo que
devuelve `/api/v1` y se envían los cambios.

- `npm install` · `npm run build` → `dist/` (Maven lo mete en el jar).
- `npm run dev` → http://127.0.0.1:5173 con el backend arrancado en 5001.
- Sin Node en el PC: `cd ../backend && mvnw.cmd -Pinterfaz clean verify`.

El zip de cada entrega ya trae `dist/` compilado.

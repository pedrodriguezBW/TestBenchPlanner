import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { ProveedorPlanificador } from './estado/Planificador';
import './estilos/base-4.9.4.css';
import './estilos/aplicacion.css';

createRoot(document.getElementById('raiz')!).render(
  <StrictMode>
    <ProveedorPlanificador>
      <App />
    </ProveedorPlanificador>
  </StrictMode>,
);

import { HISTORIAL_4_9_4 } from './acercaDe494';

/*
 * ℹ️ About (renderAbout de la 4.9.4): cabecera con la versión y, debajo, el
 * contenido original de la 4.9.4 (Highlights y Changelog) como histórico.
 */
export function AcercaDe({ version }: { version: string }) {
  return (
    <div className="sec" id="acerca-de">
      <div className="about-card" style={{ display: 'flex', gap: 16, alignItems: 'center', marginBottom: 14 }}>
        <img src="/ciudad.jpg" alt="" style={{ width: 120, height: 70, objectFit: 'cover', borderRadius: 10, border: '1px solid var(--line)' }} />
        <div>
          <h2 style={{ margin: 0 }}>BorgWarner STC Testing Department</h2>
          <div className="hint">Test Bench Planning &middot; Version {version} (React + Java) &middot; migración funcional de la 4.9.4 (build 494, 2026-10-02)</div>
        </div>
      </div>
      {/* Texto estático copiado de la 4.9.4 (sin datos de usuario). */}
      <div dangerouslySetInnerHTML={{ __html: HISTORIAL_4_9_4 }} />
    </div>
  );
}

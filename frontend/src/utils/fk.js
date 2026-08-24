// Compartido entre App.jsx (export CSV) y Spreadsheet.jsx (grilla): ambos necesitan
// traducir una resolución de FK ({status, value}, ver DatabaseService.resolveForeignKeys)
// al mismo texto legible, así que vive en un módulo aparte en vez de que uno importe del otro.

// Texto legible para una celda FK ya resuelta por el backend.
// null = "sin valor real que mostrar" (NULL o sin resolución); el llamador decide el fallback.
export function fkStatusText(resolution) {
  if (!resolution) return null;
  if (resolution.status === 'RESOLVED') return resolution.value;
  if (resolution.status === 'NULL') return null;
  return 'No encontrado'; // ORPHAN: la FK tiene id pero no hay registro coincidente
}

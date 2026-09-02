// Compartido por los distintos flujos de exportación CSV del frontend (App.jsx
// y, potencialmente, CustomReports.jsx). Neutraliza "CSV/Formula Injection": si un
// valor de celda empieza con un carácter que Excel/Sheets interpretan como inicio
// de fórmula, se le antepone un apóstrofe para forzar que se lea como texto plano.
// Espeja DatabaseService.sanitizeForSpreadsheet del backend (Java) — mismo propósito.
const RISKY_SPREADSHEET_PREFIXES = ['=', '+', '-', '@', '\t', '\r'];

export function sanitizeForSpreadsheet(value) {
  const str = String(value);
  return RISKY_SPREADSHEET_PREFIXES.includes(str.charAt(0)) ? `'${str}` : str;
}

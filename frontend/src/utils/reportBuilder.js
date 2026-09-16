// Heurística compartida para sugerir la "mejor" columna descriptiva de una tabla recién unida
// (por ejemplo, mostrar "razon_social" en vez del "id" crudo de una FK). Vivía duplicada de
// forma casi idéntica en CustomReports.jsx y JoinBuilderPanel.jsx (ver auditoría, hallazgo de
// duplicación); centralizarla evita que diverjan silenciosamente al ajustar una y olvidar la otra.
const DESCRIPTIVE_NAME_HINTS = ['nombre', 'desc', 'glosa', 'razon', 'giro', 'titulo'];

/**
 * @param {Array<{name: string}>} columns
 * @returns {{name: string} | null} la columna elegida, o null si `columns` está vacío
 */
export function pickDescriptiveColumn(columns) {
  if (!columns || columns.length === 0) return null;

  const byHint = columns.find((c) => {
    const n = c.name.toLowerCase();
    return DESCRIPTIVE_NAME_HINTS.some((hint) => n.includes(hint)) && !n.startsWith('id');
  });
  if (byHint) return byHint;

  const nonId = columns.find((c) => !c.name.toLowerCase().startsWith('id'));
  if (nonId) return nonId;

  return columns[0];
}

/**
 * Alias correlativo único ("t1", "t2", ...) para la siguiente tabla unida en el constructor
 * de reportes, a partir de los joins ya configurados.
 * @param {Array<{table?: {alias?: string}}>} joins
 */
export function getNextAlias(joins) {
  let maxNum = 0;
  (joins || []).forEach((j) => {
    if (j.table && j.table.alias) {
      const m = j.table.alias.match(/^t(\d+)$/);
      if (m) maxNum = Math.max(maxNum, parseInt(m[1], 10));
    }
  });
  return `t${maxNum + 1}`;
}

/**
 * Arma el payload que espera POST /api/db/custom-reports/preview a partir del estado del
 * constructor de reportes. Función pura y testeable, separada de la lógica de UI de
 * CustomReports.jsx (ver auditoría, hallazgo: lógica de negocio mezclada con el componente).
 */
export function buildQueryPayload({ baseTable, joins, selectedColumns, filters, sorts, page, limit, distinct }) {
  if (!baseTable) return null;
  const offset = (page - 1) * limit;
  return {
    baseTable: {
      schema: baseTable.schema,
      name: baseTable.name,
      alias: baseTable.alias
    },
    joins: (joins || [])
      .filter((j) => j.table && j.table.name && j.onLeft.column && j.onRight.column)
      .map((j) => ({
        type: j.type,
        table: j.table,
        onLeft: j.onLeft,
        onRight: j.onRight
      })),
    columns: selectedColumns,
    filters: (filters || []).filter(
      (f) => f.column && (f.operator === 'IS NULL' || f.operator === 'IS NOT NULL' || f.value !== '')
    ),
    sorts: (sorts || []).filter((s) => s.column),
    limit,
    offset,
    distinct
  };
}

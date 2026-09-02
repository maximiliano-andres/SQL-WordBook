// Operadores de filtrado permitidos en el constructor de reportes personalizados.
// Vive en su propio módulo (en vez de dentro de FilterSortPanel.jsx) para que ese
// archivo solo exporte el componente y no rompa el fast-refresh de React.
export const ALLOWED_OPERATORS = [
  { value: 'LIKE', label: 'contiene (LIKE)', unary: false },
  { value: 'NOT LIKE', label: 'no contiene (NOT LIKE)', unary: false },
  { value: '=', label: 'igual a (=)', unary: false },
  { value: '!=', label: 'distinto de (!=)', unary: false },
  { value: '>', label: 'mayor que (>)', unary: false },
  { value: '<', label: 'menor que (<)', unary: false },
  { value: '>=', label: 'mayor o igual que (>=)', unary: false },
  { value: '<=', label: 'menor o igual que (<=)', unary: false },
  { value: 'BETWEEN', label: 'está entre (BETWEEN)', unary: false, between: true },
  { value: 'IN', label: 'está en lista (IN)', unary: false },
  { value: 'IS NULL', label: 'está vacío (IS NULL)', unary: true },
  { value: 'IS NOT NULL', label: 'no está vacío (IS NOT NULL)', unary: true }
];

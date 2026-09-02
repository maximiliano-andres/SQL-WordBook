import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
  Play, Download, Save, FolderOpen, Plus, Trash2, Edit3,
  ChevronDown, ChevronUp, ChevronLeft, ChevronRight, Copy, Check,
  Layers, Link2, Code2, Sparkles, RefreshCw, Filter, Eye,
  Settings2, X, ArrowUpDown, FileSpreadsheet, Info, AlertCircle
} from 'lucide-react';

const ALLOWED_OPERATORS = [
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

export default function CustomReports({
  tables = [],
  columnsCache = {},
  setColumnsCache = () => {}
}) {
  // --- Estados de Plantilla / Configuración ---
  const [reportId, setReportId] = useState(null);
  const [reportName, setReportName] = useState('Nuevo Reporte Personalizado');
  const [reportDescription, setReportDescription] = useState('');
  const [templates, setTemplates] = useState([]);
  const [showTemplatesModal, setShowTemplatesModal] = useState(false);
  const [showSaveModal, setShowSaveModal] = useState(false);
  const [tempReportName, setTempReportName] = useState('');
  const [tempReportDesc, setTempReportDesc] = useState('');

  // --- Estados del Constructor ---
  // Tabla base: { schema, name, alias: 't0' }
  const [baseTable, setBaseTable] = useState(null);
  // Joins: [ { id, type: 'LEFT', table: { schema, name, alias: 't1' }, onLeft: { tableAlias: 't0', column: '' }, onRight: { tableAlias: 't1', column: '' } } ]
  const [joins, setJoins] = useState([]);
  // Columnas seleccionadas: [ { tableAlias: 't0', column: 'id', label: 'ID Factura' } ]
  const [selectedColumns, setSelectedColumns] = useState([]);
  // Filtros: [ { id, tableAlias: 't0', column: '', operator: 'LIKE', value: '', value2: '', logic: 'AND' } ]
  const [filters, setFilters] = useState([]);
  // Ordenamiento: [ { id, tableAlias: 't0', column: '', direction: 'ASC' } ]
  const [sorts, setSorts] = useState([]);

  // Sugerencias de Joins basadas en FKs
  const [suggestedJoins, setSuggestedJoins] = useState([]);
  const [isLoadingSuggestions, setIsLoadingSuggestions] = useState(false);

  // --- Estados de Ejecución / Paginación ---
  const [limit, setLimit] = useState(15);
  const [page, setPage] = useState(1);
  const [totalRows, setTotalRows] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [reportData, setReportData] = useState([]);
  const [reportResultColumns, setReportResultColumns] = useState([]);
  const [generatedSql, setGeneratedSql] = useState('');
  const [executionTime, setExecutionTime] = useState(null);
  const [isExecuting, setIsExecuting] = useState(false);
  const [isExportingExcel, setIsExportingExcel] = useState(false);
  const [queryError, setQueryError] = useState(null);
  const [isDistinct, setIsDistinct] = useState(false); // Flag para eliminar duplicados (SELECT DISTINCT)

  // Vistas y paneles colapsables
  const [activeStep, setActiveStep] = useState('builder'); // 'builder' | 'preview'
  const [showSqlViewer, setShowSqlViewer] = useState(false);
  const [copiedSql, setCopiedSql] = useState(false);
  const [activeColumnTable, setActiveColumnTable] = useState('');
  const [columnSearchQuery, setColumnSearchQuery] = useState('');

  // Cache local de columnas para tablas seleccionadas
  const fetchColumnsForTable = useCallback(async (schema, name) => {
    const key = `${schema}.${name}`;
    if (columnsCache[key]) {
      return columnsCache[key];
    }
    try {
      const res = await fetch(`/api/db/tables/${encodeURIComponent(schema)}/${encodeURIComponent(name)}/columns`);
      if (res.ok) {
        const cols = await res.json();
        setColumnsCache(prev => ({ ...prev, [key]: cols }));
        return cols;
      }
    } catch (err) {
      console.error('Error fetching columns for', key, err);
    }
    return [];
  }, [columnsCache, setColumnsCache]);

  // Lista de todas las tablas activas en la consulta actual (base + unidas)
  const participatingTables = useMemo(() => {
    const list = [];
    if (baseTable) {
      list.push({ ...baseTable, isBase: true });
    }
    joins.forEach(j => {
      if (j.table && j.table.name) {
        list.push({ ...j.table, isBase: false, joinId: j.id });
      }
    });
    return list;
  }, [baseTable, joins]);

  // Asegurar que las columnas de todas las tablas participantes estén en caché
  useEffect(() => {
    participatingTables.forEach(t => {
      fetchColumnsForTable(t.schema, t.name);
    });
  }, [participatingTables, fetchColumnsForTable]);

  // Inicializar activeColumnTable si no está asignada
  useEffect(() => {
    if (participatingTables.length > 0 && (!activeColumnTable || !participatingTables.some(t => t.alias === activeColumnTable))) {
      setActiveColumnTable(participatingTables[0].alias);
    }
  }, [participatingTables, activeColumnTable]);

  // Cargar plantillas de reportes guardadas al montar
  const loadTemplates = useCallback(async () => {
    try {
      const res = await fetch('/api/db/custom-reports/templates');
      if (res.ok) {
        const data = await res.json();
        setTemplates(data);
      }
    } catch (e) {
      console.error('Error cargando plantillas', e);
    }
  }, []);

  useEffect(() => {
    loadTemplates();
  }, [loadTemplates]);

  // Generar alias único correlativo para nuevas tablas unidas
  const getNextAlias = useCallback(() => {
    let maxNum = 0;
    joins.forEach(j => {
      if (j.table && j.table.alias) {
        const m = j.table.alias.match(/^t(\d+)$/);
        if (m) maxNum = Math.max(maxNum, parseInt(m[1], 10));
      }
    });
    return `t${maxNum + 1}`;
  }, [joins]);

  // Cargar sugerencias de joins para todas las tablas participantes en la consulta
  const loadJoinSuggestionsForTables = useCallback(async (currentTables) => {
    if (!currentTables || currentTables.length === 0) {
      setSuggestedJoins([]);
      return;
    }
    setIsLoadingSuggestions(true);
    try {
      const allSuggestions = [];
      for (const t of currentTables) {
        const res = await fetch(`/api/db/custom-reports/suggest-joins?schema=${encodeURIComponent(t.schema)}&table=${encodeURIComponent(t.name)}`);
        if (res.ok) {
          const list = await res.json();
          list.forEach(item => {
            allSuggestions.push({
              ...item,
              originTableAlias: t.alias,
              originTableName: t.name,
              originSchema: t.schema
            });
          });
        }
      }
      // Deduplicar sugerencias
      const seen = new Set();
      const unique = allSuggestions.filter(s => {
        const sig = `${s.originTableAlias}.${s.sourceSchema}.${s.sourceTable}.${s.sourceColumn}->${s.targetSchema}.${s.targetTable}.${s.targetColumn}`;
        if (seen.has(sig)) return false;
        seen.add(sig);
        return true;
      });
      setSuggestedJoins(unique);
    } catch (e) {
      console.error('Error cargando sugerencias de cruces', e);
    } finally {
      setIsLoadingSuggestions(false);
    }
  }, []);

  // Seleccionar tabla base inicial
  const handleSelectBaseTable = (tableKey) => {
    if (!tableKey) {
      setBaseTable(null);
      setJoins([]);
      setSelectedColumns([]);
      setFilters([]);
      setSorts([]);
      setSuggestedJoins([]);
      return;
    }
    const [schema, name] = tableKey.split('.');
    const newBase = { schema, name, alias: 't0' };
    setBaseTable(newBase);
    setJoins([]);
    setFilters([]);
    setSorts([]);
    loadJoinSuggestionsForTables([newBase]);

    // Seleccionar por defecto columnas de la tabla base
    fetchColumnsForTable(schema, name).then(cols => {
      if (cols && cols.length > 0) {
        setSelectedColumns(cols.map(c => ({
          tableAlias: 't0',
          column: c.name,
          label: c.name
        })));
      }
    });
  };

  // Agregar un cruce (Join) sugerido automáticamente por FK
  const handleApplySuggestedJoin = async (sug) => {
    const originTable = participatingTables.find(t => t.alias === sug.originTableAlias) || baseTable;
    const isTargetOther = (sug.sourceSchema === originTable.schema && sug.sourceTable === originTable.name);
    const targetSchema = isTargetOther ? sug.targetSchema : sug.sourceSchema;
    const targetName = isTargetOther ? sug.targetTable : sug.sourceTable;
    const leftCol = isTargetOther ? sug.sourceColumn : sug.targetColumn;
    const rightCol = isTargetOther ? sug.targetColumn : sug.sourceColumn;

    const newAlias = getNextAlias();
    const newJoin = {
      id: String(Date.now()) + Math.random(),
      type: sug.type || 'LEFT',
      table: { schema: targetSchema, name: targetName, alias: newAlias },
      onLeft: { tableAlias: originTable.alias, column: leftCol },
      onRight: { tableAlias: newAlias, column: rightCol }
    };

    setJoins(prev => {
      const updated = [...prev, newJoin];
      // Recargar sugerencias para todas las tablas involucradas
      setTimeout(() => {
        const allParticipating = [baseTable, ...updated.map(j => j.table)];
        loadJoinSuggestionsForTables(allParticipating);
      }, 100);
      return updated;
    });

    const cols = await fetchColumnsForTable(targetSchema, targetName);
    if (cols && cols.length > 0) {
      // Agregar las primeras 3 columnas representativas de la tabla unida
      const candidateCols = cols.slice(0, 3);
      setSelectedColumns(prev => [
        ...prev,
        ...candidateCols.map(c => ({
          tableAlias: newAlias,
          column: c.name,
          label: `${targetName} - ${c.name}`
        }))
      ]);
    }
  };

  // Agregar un cruce manual
  const handleAddManualJoin = () => {
    if (!tables || tables.length === 0 || !baseTable) return;
    const defaultLeftTable = participatingTables[participatingTables.length - 1] || baseTable;
    const defaultTargetTable = tables.find(t => !participatingTables.some(pt => pt.schema === t.schema && pt.name === t.name)) || tables[0];

    const newAlias = getNextAlias();
    const newJoin = {
      id: String(Date.now()) + Math.random(),
      type: 'LEFT',
      table: { schema: defaultTargetTable.schema, name: defaultTargetTable.name, alias: newAlias },
      onLeft: { tableAlias: defaultLeftTable.alias, column: '' },
      onRight: { tableAlias: newAlias, column: '' }
    };
    setJoins(prev => [...prev, newJoin]);
    fetchColumnsForTable(defaultTargetTable.schema, defaultTargetTable.name);
  };

  // Actualizar un Join
  const handleUpdateJoin = (id, field, value) => {
    setJoins(prev => prev.map(j => {
      if (j.id !== id) return j;
      if (field === 'tableKey') {
        const [schema, name] = value.split('.');
        fetchColumnsForTable(schema, name);
        return {
          ...j,
          table: { ...j.table, schema, name },
          onRight: { ...j.onRight, column: '' }
        };
      }
      if (field === 'type') {
        return { ...j, type: value };
      }
      if (field === 'onLeftTableAlias') {
        return { ...j, onLeft: { tableAlias: value, column: '' } };
      }
      if (field === 'onLeftColumn') {
        return { ...j, onLeft: { ...j.onLeft, column: value } };
      }
      if (field === 'onRightColumn') {
        return { ...j, onRight: { ...j.onRight, column: value } };
      }
      return j;
    }));
  };

  // Eliminar un Join
  const handleDeleteJoin = (id) => {
    const targetJoin = joins.find(j => j.id === id);
    if (targetJoin) {
      // Eliminar también columnas, filtros y ordenamientos asociados a ese alias
      setSelectedColumns(prev => prev.filter(c => c.tableAlias !== targetJoin.table.alias));
      setFilters(prev => prev.filter(f => f.tableAlias !== targetJoin.table.alias));
      setSorts(prev => prev.filter(s => s.tableAlias !== targetJoin.table.alias));
    }
    setJoins(prev => {
      const updated = prev.filter(j => j.id !== id);
      if (baseTable) {
        const allParticipating = [baseTable, ...updated.map(j => j.table)];
        loadJoinSuggestionsForTables(allParticipating);
      }
      return updated;
    });
  };

  // Toggle selección de columna
  const handleToggleColumn = (tableAlias, colName) => {
    const exists = selectedColumns.some(c => c.tableAlias === tableAlias && c.column === colName);
    if (exists) {
      setSelectedColumns(prev => prev.filter(c => !(c.tableAlias === tableAlias && c.column === colName)));
    } else {
      const tableObj = participatingTables.find(t => t.alias === tableAlias);
      const label = tableObj && !tableObj.isBase ? `${tableObj.name} - ${colName}` : colName;
      setSelectedColumns(prev => [...prev, { tableAlias, column: colName, label }]);
    }
  };

  // Marcar todas las columnas de una tabla
  const handleSelectAllColumnsOfTable = (tableAlias) => {
    const tableObj = participatingTables.find(t => t.alias === tableAlias);
    if (!tableObj) return;
    const cols = columnsCache[`${tableObj.schema}.${tableObj.name}`] || [];
    const otherCols = selectedColumns.filter(c => c.tableAlias !== tableAlias);
    const newCols = cols.map(c => ({
      tableAlias,
      column: c.name,
      label: !tableObj.isBase ? `${tableObj.name} - ${c.name}` : c.name
    }));
    setSelectedColumns([...otherCols, ...newCols]);
  };

  // Desmarcar todas las columnas de una tabla
  const handleDeselectAllColumnsOfTable = (tableAlias) => {
    setSelectedColumns(prev => prev.filter(c => c.tableAlias !== tableAlias));
  };

  // Cambiar alias/etiqueta de una columna
  const handleUpdateColumnLabel = (tableAlias, colName, newLabel) => {
    setSelectedColumns(prev => prev.map(c => {
      if (c.tableAlias === tableAlias && c.column === colName) {
        return { ...c, label: newLabel };
      }
      return c;
    }));
  };

  // Agregar Filtro
  const handleAddFilter = () => {
    if (participatingTables.length === 0) return;
    const firstTable = participatingTables[0];
    const cols = columnsCache[`${firstTable.schema}.${firstTable.name}`] || [];
    const firstCol = cols.length > 0 ? cols[0].name : '';

    setFilters(prev => [
      ...prev,
      {
        id: String(Date.now()) + Math.random(),
        tableAlias: firstTable.alias,
        column: firstCol,
        operator: 'LIKE',
        value: '',
        value2: '',
        logic: 'AND'
      }
    ]);
  };

  // Actualizar Filtro
  const handleUpdateFilter = (id, field, value) => {
    setFilters(prev => prev.map(f => {
      if (f.id !== id) return f;
      if (field === 'tableAlias') {
        const tableObj = participatingTables.find(t => t.alias === value);
        const cols = tableObj ? (columnsCache[`${tableObj.schema}.${tableObj.name}`] || []) : [];
        return { ...f, tableAlias: value, column: cols.length > 0 ? cols[0].name : '' };
      }
      return { ...f, [field]: value };
    }));
  };

  // Eliminar Filtro
  const handleDeleteFilter = (id) => {
    setFilters(prev => prev.filter(f => f.id !== id));
  };

  // Agregar Ordenamiento
  const handleAddSort = () => {
    if (participatingTables.length === 0) return;
    const firstTable = participatingTables[0];
    const cols = columnsCache[`${firstTable.schema}.${firstTable.name}`] || [];
    const firstCol = cols.length > 0 ? cols[0].name : '';
    setSorts(prev => [
      ...prev,
      {
        id: String(Date.now()) + Math.random(),
        tableAlias: firstTable.alias,
        column: firstCol,
        direction: 'ASC'
      }
    ]);
  };

  // Actualizar Ordenamiento
  const handleUpdateSort = (id, field, value) => {
    setSorts(prev => prev.map(s => {
      if (s.id !== id) return s;
      if (field === 'tableAlias') {
        const tableObj = participatingTables.find(t => t.alias === value);
        const cols = tableObj ? (columnsCache[`${tableObj.schema}.${tableObj.name}`] || []) : [];
        return { ...s, tableAlias: value, column: cols.length > 0 ? cols[0].name : '' };
      }
      return { ...s, [field]: value };
    }));
  };

  // Eliminar Ordenamiento
  const handleDeleteSort = (id) => {
    setSorts(prev => prev.filter(s => s.id !== id));
  };

  // Construir el payload de consulta
  const buildQueryPayload = useCallback((customPage = page, customLimit = limit, customDistinct = isDistinct) => {
    if (!baseTable) return null;
    const offset = (customPage - 1) * customLimit;
    return {
      baseTable: {
        schema: baseTable.schema,
        name: baseTable.name,
        alias: baseTable.alias
      },
      joins: joins.filter(j => j.table && j.table.name && j.onLeft.column && j.onRight.column).map(j => ({
        type: j.type,
        table: j.table,
        onLeft: j.onLeft,
        onRight: j.onRight
      })),
      columns: selectedColumns,
      filters: filters.filter(f => f.column && (f.operator === 'IS NULL' || f.operator === 'IS NOT NULL' || f.value !== '')),
      sorts: sorts.filter(s => s.column),
      limit: customLimit,
      offset,
      distinct: customDistinct
    };
  }, [baseTable, joins, selectedColumns, filters, sorts, page, limit, isDistinct]);

  // Ejecutar Consulta
  const handleExecuteQuery = async (targetPage = 1, targetLimit = limit, targetDistinct = isDistinct) => {
    if (!baseTable) {
      setQueryError('Por favor selecciona una tabla base para el reporte.');
      return;
    }
    if (selectedColumns.length === 0) {
      setQueryError('Debes seleccionar al menos una columna para el reporte.');
      return;
    }

    setIsExecuting(true);
    setQueryError(null);
    const query = buildQueryPayload(targetPage, targetLimit, targetDistinct);

    try {
      const res = await fetch('/api/db/custom-reports/preview', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(query)
      });

      if (!res.ok) {
        const errJson = await res.json().catch(() => ({}));
        throw new Error(errJson.error || 'Error al ejecutar la consulta del reporte.');
      }

      const result = await res.json();
      setReportData(result.data || []);
      setTotalRows(result.totalRows || 0);
      setTotalPages(result.totalPages || 1);
      setPage(result.currentPage || 1);
      setExecutionTime(result.executionTimeMs || 0);
      setGeneratedSql(result.generatedSql || '');
      setReportResultColumns(result.columns || []);
      setActiveStep('preview');
    } catch (err) {
      console.error(err);
      setQueryError(err.message);
    } finally {
      setIsExecuting(false);
    }
  };

  // Alternar eliminación de duplicados (DISTINCT)
  const handleToggleDistinct = () => {
    const nextDistinct = !isDistinct;
    setIsDistinct(nextDistinct);
    handleExecuteQuery(1, limit, nextDistinct);
  };

  // Exportar a Excel (.xlsx)
  const handleExportExcel = async () => {
    if (!baseTable || selectedColumns.length === 0) return;
    setIsExportingExcel(true);
    const query = buildQueryPayload(1, 1000000, isDistinct); // Sin límite para exportación

    try {
      const res = await fetch('/api/db/custom-reports/export', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(query)
      });

      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.error || 'Error al descargar el archivo de Excel.');
      }

      const blob = await res.blob();
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${reportName.toLowerCase().replace(/[^a-z0-9_]/g, '_')}_${Date.now()}.xlsx`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      window.URL.revokeObjectURL(url);
    } catch (err) {
      alert(`Error al exportar: ${err.message}`);
    } finally {
      setIsExportingExcel(false);
    }
  };

  // Exportar vista actual a CSV
  const handleExportCsv = () => {
    if (!reportData || reportData.length === 0) return;
    const cols = reportResultColumns.map(c => c.label);
    let csv = cols.map(c => `"${c.replace(/"/g, '""')}"`).join(',') + '\n';

    reportData.forEach(row => {
      const rowVals = cols.map(colName => {
        const val = row[colName];
        if (val === null || val === undefined) return '""';
        return `"${String(val).replace(/"/g, '""')}"`;
      });
      csv += rowVals.join(',') + '\n';
    });

    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${reportName.toLowerCase().replace(/[^a-z0-9_]/g, '_')}_pagina_${page}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  };

  // Guardar plantilla
  const handleSaveTemplate = async () => {
    if (!tempReportName.trim()) {
      alert('El nombre del reporte es obligatorio');
      return;
    }
    const config = {
      baseTable,
      joins,
      selectedColumns,
      filters,
      sorts,
      distinct: isDistinct
    };

    try {
      const payload = {
        id: reportId || undefined,
        name: tempReportName.trim(),
        description: tempReportDesc.trim(),
        configJson: JSON.stringify(config)
      };

      const res = await fetch('/api/db/custom-reports/templates', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });

      if (!res.ok) throw new Error('Error al guardar la plantilla.');
      const saved = await res.json();
      setReportId(saved.id);
      setReportName(saved.name);
      setReportDescription(saved.description);
      setShowSaveModal(false);
      loadTemplates();
      alert('¡Plantilla de reporte guardada con éxito en la base de datos!');
    } catch (err) {
      alert(err.message);
    }
  };

  // Cargar una plantilla
  const handleLoadTemplate = (tpl) => {
    try {
      const config = JSON.parse(tpl.configJson);
      setReportId(tpl.id);
      setReportName(tpl.name);
      setReportDescription(tpl.description || '');
      setBaseTable(config.baseTable || null);
      setJoins(config.joins || []);
      setSelectedColumns(config.selectedColumns || []);
      setFilters(config.filters || []);
      setSorts(config.sorts || []);
      setIsDistinct(Boolean(config.distinct));
      setShowTemplatesModal(false);
      if (config.baseTable) {
        const allParticipating = [config.baseTable, ...(config.joins || []).map(j => j.table)];
        loadJoinSuggestionsForTables(allParticipating);
      }
    } catch (e) {
      alert('Error al leer el archivo de configuración de la plantilla.');
    }
  };

  // Eliminar plantilla
  const handleDeleteTemplate = async (id, e) => {
    e.stopPropagation();
    if (!window.confirm('¿Seguro que deseas eliminar esta plantilla de reporte?')) return;
    try {
      const res = await fetch(`/api/db/custom-reports/templates/${encodeURIComponent(id)}`, {
        method: 'DELETE'
      });
      if (res.ok) {
        setTemplates(prev => prev.filter(t => t.id !== id));
        if (reportId === id) {
          setReportId(null);
        }
      }
    } catch (err) {
      alert('Error al eliminar la plantilla.');
    }
  };

  // Reset / Nuevo Reporte
  const handleNewReport = () => {
    setReportId(null);
    setReportName('Nuevo Reporte Personalizado');
    setReportDescription('');
    setBaseTable(null);
    setJoins([]);
    setSelectedColumns([]);
    setFilters([]);
    setSorts([]);
    setReportData([]);
    setGeneratedSql('');
    setQueryError(null);
    setActiveStep('builder');
    setSuggestedJoins([]);
    setIsDistinct(false);
  };

  return (
    <div className="custom-reports-workspace">
      {/* Barra de Gestión Superior */}
      <div className="cr-top-header">
        <div className="cr-title-area">
          <div className="cr-badge-tag">
            <Layers size={14} />
            <span>REPORTES PERSONALIZADOS</span>
          </div>
          <div className="cr-title-edit-box">
            <h2 className="cr-report-title">{reportName}</h2>
            {reportDescription && <p className="cr-report-desc">{reportDescription}</p>}
          </div>
          {totalRows > 0 && (
            <div className="cr-header-rows-badge" title="Total de registros coincidentes en la base de datos">
              <span className="cr-header-rows-dot">●</span>
              <span>Total: <strong>{totalRows.toLocaleString()}</strong> filas</span>
              {isDistinct && <small style={{ color: 'var(--excel-green-light)', fontWeight: 'bold' }}>(Sin Duplicados)</small>}
            </div>
          )}
        </div>

        <div className="cr-header-actions">
          <button
            className="excel-btn"
            onClick={() => {
              setTempReportName(reportName);
              setTempReportDesc(reportDescription);
              setShowSaveModal(true);
            }}
            title="Guardar esta consulta como plantilla reutilizable"
          >
            <Save size={14} style={{ color: 'var(--excel-green-light)' }} />
            <span>Guardar Plantilla</span>
          </button>

          <button
            className="excel-btn"
            onClick={() => {
              loadTemplates();
              setShowTemplatesModal(true);
            }}
            title="Cargar una plantilla guardada previamente"
          >
            <FolderOpen size={14} />
            <span>Mis Plantillas ({templates.length})</span>
          </button>

          <button
            className="excel-btn"
            onClick={handleNewReport}
            title="Crear un reporte nuevo desde cero"
          >
            <Plus size={14} />
            <span>Nuevo</span>
          </button>

          <div style={{ height: '20px', width: '1px', backgroundColor: 'var(--excel-border)', margin: '0 4px' }}></div>

          <div className="cr-view-pills">
            <button
              className={`cr-view-pill ${activeStep === 'builder' ? 'active' : ''}`}
              onClick={() => setActiveStep('builder')}
            >
              <Settings2 size={13} />
              <span>Diseñador</span>
            </button>
            <button
              className={`cr-view-pill ${activeStep === 'preview' ? 'active' : ''}`}
              onClick={() => setActiveStep('preview')}
            >
              <Eye size={13} />
              <span>Resultados {reportData.length > 0 ? `(${totalRows.toLocaleString()})` : ''}</span>
            </button>
          </div>

          <button
            className="excel-btn primary"
            onClick={() => handleExecuteQuery(1, limit)}
            disabled={isExecuting || !baseTable || selectedColumns.length === 0}
            style={{ backgroundColor: 'var(--excel-green)', borderColor: 'var(--excel-green)', fontWeight: 600 }}
            title="Ejecutar consulta multi-tabla con los filtros y cruces configurados"
          >
            <Play size={14} className={isExecuting ? 'animate-spin' : ''} />
            <span>{isExecuting ? 'Consultando...' : 'Ejecutar Consulta'}</span>
          </button>
        </div>
      </div>

      {queryError && (
        <div className="cr-error-banner">
          <AlertCircle size={16} />
          <span>{queryError}</span>
          <button className="cr-icon-btn" onClick={() => setQueryError(null)}><X size={14} /></button>
        </div>
      )}

      {/* Contenido Principal */}
      <div className="cr-body-content">
        {activeStep === 'builder' ? (
          <div className="cr-builder-layout">
            {/* PANEL IZQUIERDO: Configuración de Tablas y Cruces */}
            <div className="cr-panel-card">
              <div className="cr-card-header">
                <div className="cr-card-title">
                  <span className="cr-step-number">1</span>
                  <h3>Tabla Principal y Cruces (Joins)</h3>
                </div>
                <button
                  className="excel-btn"
                  style={{ fontSize: '11px', padding: '3px 8px' }}
                  onClick={handleAddManualJoin}
                  disabled={!baseTable}
                  title="Agregar un cruce manual con otra tabla"
                >
                  <Plus size={13} />
                  <span>Cruce Manual</span>
                </button>
              </div>

              {/* Selector de Tabla Base */}
              <div className="cr-section-block">
                <label className="cr-label">
                  <strong>Tabla Principal (Origen de datos):</strong>
                </label>
                <select
                  className="excel-select cr-wide-select"
                  value={baseTable ? `${baseTable.schema}.${baseTable.name}` : ''}
                  onChange={(e) => handleSelectBaseTable(e.target.value)}
                >
                  <option value="">-- Seleccionar tabla principal --</option>
                  {tables.map(t => (
                    <option key={`${t.schema}.${t.name}`} value={`${t.schema}.${t.name}`}>
                      {t.schema}.{t.name}
                    </option>
                  ))}
                </select>
              </div>

              {/* Mapa / Cadena Visual de Tablas Cruzadas */}
              {baseTable && (
                <div className="cr-chain-container">
                  <span className="cr-chain-title">Esquema de Cruces ({participatingTables.length} tablas):</span>
                  <div className="cr-tables-chain">
                    <span className="cr-chain-badge base" title="Tabla principal de origen">
                      🏠 {baseTable.name} <small>(Base)</small>
                    </span>
                    {joins.map((j, i) => (
                      <React.Fragment key={j.id}>
                        <span className="cr-chain-arrow">➔</span>
                        <span className="cr-chain-badge join" title={`${j.type} JOIN con ${j.onLeft.tableAlias}.${j.onLeft.column} = ${j.table.alias}.${j.onRight.column}`}>
                          🔗 {j.type} {j.table.name}
                        </span>
                      </React.Fragment>
                    ))}
                  </div>
                </div>
              )}

              {/* Sugerencias Inteligentes de Cruces basadas en FKs */}
              {baseTable && (
                <div className="cr-suggestions-container">
                  <div className="cr-suggestions-header">
                    <Sparkles size={14} style={{ color: '#ffd54f' }} />
                    <span>Relaciones FK detectadas ({suggestedJoins.length})</span>
                  </div>
                  {isLoadingSuggestions ? (
                    <div style={{ fontSize: '12px', color: 'var(--text-muted)' }}>Analizando claves foráneas...</div>
                  ) : suggestedJoins.length > 0 ? (
                    <div className="cr-suggestions-list">
                      {suggestedJoins.map((sug, idx) => {
                        const isAlreadyAdded = joins.some(j =>
                          (j.table.schema === sug.targetSchema && j.table.name === sug.targetTable) ||
                          (j.table.schema === sug.sourceSchema && j.table.name === sug.sourceTable)
                        );
                        return (
                          <div key={idx} className={`cr-suggestion-item ${isAlreadyAdded ? 'added' : ''}`}>
                            <div className="cr-sug-info">
                              <span className="cr-sug-desc">
                                <strong>[{sug.originTableName || baseTable.name}]</strong> ➔ {sug.description}
                              </span>
                            </div>
                            <button
                              className="excel-btn"
                              style={{ fontSize: '10.5px', padding: '2px 8px', height: '22px' }}
                              onClick={() => handleApplySuggestedJoin(sug)}
                              disabled={isAlreadyAdded}
                            >
                              {isAlreadyAdded ? <Check size={12} /> : <Plus size={12} />}
                              <span>{isAlreadyAdded ? 'Agregado' : 'Unir'}</span>
                            </button>
                          </div>
                        );
                      })}
                    </div>
                  ) : (
                    <div style={{ fontSize: '11.5px', color: 'var(--text-muted)' }}>
                      No se detectaron FKs directas. Puedes usar el botón <strong>"+ Cruce Manual"</strong> arriba.
                    </div>
                  )}
                </div>
              )}

              {/* Lista de Cruces Configurados */}
              {joins.length > 0 && (
                <div className="cr-joins-list">
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <label className="cr-label" style={{ margin: 0 }}><strong>Cruces configurados ({joins.length}):</strong></label>
                  </div>

                  {joins.map((j, idx) => {
                    const availableLeftTables = [baseTable, ...joins.slice(0, idx).map(item => item.table)].filter(Boolean);
                    const leftTableObj = availableLeftTables.find(t => t.alias === j.onLeft.tableAlias) || availableLeftTables[0];
                    const leftCols = leftTableObj ? (columnsCache[`${leftTableObj.schema}.${leftTableObj.name}`] || []) : [];
                    const rightCols = columnsCache[`${j.table.schema}.${j.table.name}`] || [];

                    return (
                      <div key={j.id} className="cr-join-card">
                        <div className="cr-join-header">
                          <span className="cr-join-idx">Cruce #{idx + 1}</span>
                          <select
                            className="excel-select cr-join-type-select"
                            value={j.type}
                            onChange={(e) => handleUpdateJoin(j.id, 'type', e.target.value)}
                          >
                            <option value="LEFT">LEFT JOIN (Mantener filas de la izquierda)</option>
                            <option value="INNER">INNER JOIN (Solo filas que coincidan en ambas)</option>
                            <option value="RIGHT">RIGHT JOIN (Mantener filas de la derecha)</option>
                            <option value="FULL">FULL JOIN (Todas las filas)</option>
                          </select>
                          <button
                            className="cr-icon-btn danger"
                            onClick={() => handleDeleteJoin(j.id)}
                            title="Eliminar este cruce"
                          >
                            <Trash2 size={13} />
                          </button>
                        </div>

                        <div className="cr-join-body">
                          <div className="cr-field-row">
                            <span className="cr-field-label">Unir tabla:</span>
                            <select
                              className="excel-select cr-join-target-select"
                              value={`${j.table.schema}.${j.table.name}`}
                              onChange={(e) => handleUpdateJoin(j.id, 'tableKey', e.target.value)}
                            >
                              {tables.map(t => (
                                <option key={`${t.schema}.${t.name}`} value={`${t.schema}.${t.name}`}>
                                  {t.schema}.{t.name}
                                </option>
                              ))}
                            </select>
                          </div>

                          <div className="cr-join-condition-block">
                            <span className="cr-on-title">Condición de enlace (ON):</span>
                            <div className="cr-join-condition-row">
                              {/* Selector de Tabla Izquierda (Permite encadenar con base o joins anteriores) */}
                              <select
                                className="excel-select cr-on-table-select"
                                value={j.onLeft.tableAlias || (leftTableObj?.alias || 't0')}
                                onChange={(e) => handleUpdateJoin(j.id, 'onLeftTableAlias', e.target.value)}
                                title="Selecciona con qué tabla se relaciona este cruce"
                              >
                                {availableLeftTables.map(t => (
                                  <option key={t.alias} value={t.alias}>
                                    {t.name} ({t.alias})
                                  </option>
                                ))}
                              </select>

                              {/* Selector de Columna Izquierda */}
                              <select
                                className="excel-select cr-on-col-select"
                                value={j.onLeft.column}
                                onChange={(e) => handleUpdateJoin(j.id, 'onLeftColumn', e.target.value)}
                              >
                                <option value="">-- Columna ({leftTableObj?.name || 'Izquierda'}) --</option>
                                {leftCols.map(c => (
                                  <option key={c.name} value={c.name}>{c.name}</option>
                                ))}
                              </select>

                              <span className="cr-equal-sign">=</span>

                              {/* Columna Derecha de la tabla que se está uniendo */}
                              <select
                                className="excel-select cr-on-col-select"
                                value={j.onRight.column}
                                onChange={(e) => handleUpdateJoin(j.id, 'onRightColumn', e.target.value)}
                              >
                                <option value="">-- Columna ({j.table.name}) --</option>
                                {rightCols.map(c => (
                                  <option key={c.name} value={c.name}>{c.name}</option>
                                ))}
                              </select>
                            </div>
                          </div>
                        </div>
                      </div>
                    );
                  })}

                  {/* Botón para agregar más cruces */}
                  <button
                    className="excel-btn"
                    style={{
                      width: '100%',
                      padding: '8px',
                      marginTop: '4px',
                      justifyContent: 'center',
                      backgroundColor: 'rgba(16, 124, 65, 0.08)',
                      borderColor: 'var(--excel-green)',
                      color: 'var(--excel-green-light)',
                      fontWeight: 600,
                      gap: '6px'
                    }}
                    onClick={handleAddManualJoin}
                    disabled={!baseTable}
                  >
                    <Plus size={14} />
                    <span>+ Agregar Otro Cruce (Unir otra tabla)</span>
                  </button>
                </div>
              )}
            </div>

            {/* PANEL CENTRAL: Selección y Renombrado de Columnas */}
            <div className="cr-panel-card cr-columns-panel">
              <div className="cr-card-header">
                <div className="cr-card-title">
                  <span className="cr-step-number">2</span>
                  <h3>Columnas del Reporte ({selectedColumns.length})</h3>
                </div>
              </div>

              {participatingTables.length === 0 ? (
                <div className="cr-empty-placeholder">
                  <Info size={24} style={{ color: 'var(--text-muted)' }} />
                  <p>Selecciona primero una tabla principal en el paso 1.</p>
                </div>
              ) : (
                <div className="cr-columns-container">
                  {/* Selector de Tabla para examinar columnas */}
                  <div className="cr-table-tabs-bar">
                    {participatingTables.map(t => {
                      const countSelected = selectedColumns.filter(c => c.tableAlias === t.alias).length;
                      return (
                        <button
                          key={t.alias}
                          className={`cr-table-tab ${activeColumnTable === t.alias ? 'active' : ''}`}
                          onClick={() => setActiveColumnTable(t.alias)}
                        >
                          <span>{t.name}</span>
                          {countSelected > 0 && <span className="cr-col-badge">{countSelected}</span>}
                        </button>
                      );
                    })}
                  </div>

                  {/* Barra de búsqueda y acciones rápidas */}
                  <div className="cr-columns-filter-bar">
                    <input
                      type="text"
                      className="excel-input cr-search-input"
                      placeholder="Buscar columna..."
                      value={columnSearchQuery}
                      onChange={(e) => setColumnSearchQuery(e.target.value)}
                    />
                    <div style={{ display: 'flex', gap: '6px' }}>
                      <button
                        className="excel-btn"
                        style={{ fontSize: '11px', padding: '2px 8px' }}
                        onClick={() => handleSelectAllColumnsOfTable(activeColumnTable)}
                      >
                        Marcar todas
                      </button>
                      <button
                        className="excel-btn"
                        style={{ fontSize: '11px', padding: '2px 8px' }}
                        onClick={() => handleDeselectAllColumnsOfTable(activeColumnTable)}
                      >
                        Desmarcar
                      </button>
                    </div>
                  </div>

                  {/* Lista de Columnas de la tabla seleccionada */}
                  <div className="cr-columns-grid">
                    {(() => {
                      const curTable = participatingTables.find(t => t.alias === activeColumnTable);
                      if (!curTable) return null;
                      const cols = columnsCache[`${curTable.schema}.${curTable.name}`] || [];
                      const filtered = cols.filter(c => c.name.toLowerCase().includes(columnSearchQuery.toLowerCase()));

                      if (filtered.length === 0) {
                        return <div style={{ padding: '16px', color: 'var(--text-muted)', fontSize: '12px' }}>No se encontraron columnas.</div>;
                      }

                      return filtered.map(col => {
                        const selObj = selectedColumns.find(c => c.tableAlias === curTable.alias && c.column === col.name);
                        const isSelected = Boolean(selObj);

                        return (
                          <div key={col.name} className={`cr-column-item ${isSelected ? 'selected' : ''}`}>
                            <label className="cr-col-checkbox-label">
                              <input
                                type="checkbox"
                                checked={isSelected}
                                onChange={() => handleToggleColumn(curTable.alias, col.name)}
                                style={{ accentColor: 'var(--excel-green-light)', cursor: 'pointer' }}
                              />
                              <span className="cr-col-orig-name">{col.name}</span>
                              <span className="cr-col-type-tag">{col.type}</span>
                            </label>

                            {isSelected && (
                              <div className="cr-col-alias-box">
                                <span className="cr-col-as-text">como:</span>
                                <input
                                  type="text"
                                  className="excel-input cr-alias-input"
                                  value={selObj.label}
                                  onChange={(e) => handleUpdateColumnLabel(curTable.alias, col.name, e.target.value)}
                                  placeholder={col.name}
                                  title="Nombre de la columna en el encabezado del reporte"
                                />
                              </div>
                            )}
                          </div>
                        );
                      });
                    })()}
                  </div>
                </div>
              )}
            </div>

            {/* PANEL DERECHO: Filtros Avanzados y Ordenamiento */}
            <div className="cr-panel-card">
              <div className="cr-card-header">
                <div className="cr-card-title">
                  <span className="cr-step-number">3</span>
                  <h3>Filtros y Ordenamiento</h3>
                </div>
                <div style={{ display: 'flex', gap: '6px' }}>
                  <button
                    className="excel-btn"
                    style={{ fontSize: '11px', padding: '3px 8px' }}
                    onClick={handleAddFilter}
                    disabled={participatingTables.length === 0}
                  >
                    <Plus size={13} />
                    <span>Filtro</span>
                  </button>
                  <button
                    className="excel-btn"
                    style={{ fontSize: '11px', padding: '3px 8px' }}
                    onClick={handleAddSort}
                    disabled={participatingTables.length === 0}
                  >
                    <ArrowUpDown size={13} />
                    <span>Orden</span>
                  </button>
                </div>
              </div>

              {/* Filtros */}
              <div className="cr-filters-section">
                <label className="cr-label"><strong>Condiciones de Filtrado ({filters.length}):</strong></label>
                {filters.length === 0 ? (
                  <div style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '16px' }}>
                    Sin filtros (se consultarán todas las filas). Haz clic en <strong>"+ Filtro"</strong> para acotar datos.
                  </div>
                ) : (
                  <div className="cr-rules-list">
                    {filters.map((f, idx) => {
                      const curTable = participatingTables.find(t => t.alias === f.tableAlias);
                      const cols = curTable ? (columnsCache[`${curTable.schema}.${curTable.name}`] || []) : [];
                      const opMeta = ALLOWED_OPERATORS.find(o => o.value === f.operator) || ALLOWED_OPERATORS[0];

                      return (
                        <div key={f.id} className="cr-rule-item">
                          {idx > 0 && (
                            <select
                              className="excel-select cr-logic-select"
                              value={f.logic}
                              onChange={(e) => handleUpdateFilter(f.id, 'logic', e.target.value)}
                            >
                              <option value="AND">Y (AND)</option>
                              <option value="OR">O (OR)</option>
                            </select>
                          )}

                          <div className="cr-rule-row">
                            <select
                              className="excel-select cr-rule-table-select"
                              value={f.tableAlias}
                              onChange={(e) => handleUpdateFilter(f.id, 'tableAlias', e.target.value)}
                            >
                              {participatingTables.map(t => (
                                <option key={t.alias} value={t.alias}>{t.name}</option>
                              ))}
                            </select>

                            <select
                              className="excel-select cr-rule-col-select"
                              value={f.column}
                              onChange={(e) => handleUpdateFilter(f.id, 'column', e.target.value)}
                            >
                              {cols.map(c => (
                                <option key={c.name} value={c.name}>{c.name}</option>
                              ))}
                            </select>

                            <select
                              className="excel-select cr-rule-op-select"
                              value={f.operator}
                              onChange={(e) => handleUpdateFilter(f.id, 'operator', e.target.value)}
                            >
                              {ALLOWED_OPERATORS.map(op => (
                                <option key={op.value} value={op.value}>{op.label}</option>
                              ))}
                            </select>

                            {!opMeta.unary && (
                              <input
                                type="text"
                                className="excel-input cr-rule-val-input"
                                value={f.value}
                                onChange={(e) => handleUpdateFilter(f.id, 'value', e.target.value)}
                                placeholder={opMeta.between ? "Desde" : "Valor..."}
                              />
                            )}

                            {opMeta.between && (
                              <input
                                type="text"
                                className="excel-input cr-rule-val-input"
                                value={f.value2}
                                onChange={(e) => handleUpdateFilter(f.id, 'value2', e.target.value)}
                                placeholder="Hasta"
                              />
                            )}

                            <button
                              className="cr-icon-btn danger"
                              onClick={() => handleDeleteFilter(f.id)}
                              title="Eliminar condición"
                            >
                              <Trash2 size={13} />
                            </button>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>

              {/* Ordenamiento */}
              <div className="cr-sorts-section">
                <label className="cr-label"><strong>Ordenamiento de Resultados ({sorts.length}):</strong></label>
                {sorts.length === 0 ? (
                  <div style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
                    Orden por defecto del motor. Haz clic en <strong>"+ Orden"</strong> para ordenar por columnas.
                  </div>
                ) : (
                  <div className="cr-rules-list">
                    {sorts.map(s => {
                      const curTable = participatingTables.find(t => t.alias === s.tableAlias);
                      const cols = curTable ? (columnsCache[`${curTable.schema}.${curTable.name}`] || []) : [];

                      return (
                        <div key={s.id} className="cr-rule-row">
                          <select
                            className="excel-select cr-rule-table-select"
                            value={s.tableAlias}
                            onChange={(e) => handleUpdateSort(s.id, 'tableAlias', e.target.value)}
                          >
                            {participatingTables.map(t => (
                              <option key={t.alias} value={t.alias}>{t.name}</option>
                            ))}
                          </select>

                          <select
                            className="excel-select cr-rule-col-select"
                            value={s.column}
                            onChange={(e) => handleUpdateSort(s.id, 'column', e.target.value)}
                          >
                            {cols.map(c => (
                              <option key={c.name} value={c.name}>{c.name}</option>
                            ))}
                          </select>

                          <select
                            className="excel-select cr-rule-op-select"
                            value={s.direction}
                            onChange={(e) => handleUpdateSort(s.id, 'direction', e.target.value)}
                          >
                            <option value="ASC">Ascendente (A-Z, 0-9)</option>
                            <option value="DESC">Descendente (Z-A, 9-0)</option>
                          </select>

                          <button
                            className="cr-icon-btn danger"
                            onClick={() => handleDeleteSort(s.id)}
                            title="Eliminar criterio de orden"
                          >
                            <Trash2 size={13} />
                          </button>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
            </div>
          </div>
        ) : (
          /* VISTA DE RESULTADOS (SPREADSHEET PREVIEW) */
          <div className="cr-results-layout">
            {/* Barra de Acciones de Resultados */}
            <div className="cr-results-toolbar">
              <div className="cr-results-stats">
                <div className="cr-stat-total-card" title="Cantidad total de registros encontrados por la consulta">
                  <span className="cr-stat-total-label">TOTAL DE FILAS:</span>
                  <span className="cr-stat-total-num">{totalRows.toLocaleString()}</span>
                </div>
                {executionTime !== null && (
                  <span className="cr-stat-pill">
                    ⏱️ <strong>{executionTime} ms</strong>
                  </span>
                )}
                <span className="cr-stat-pill">
                  Página <strong>{page}</strong> de <strong>{totalPages || 1}</strong>
                </span>
                {isDistinct && (
                  <span
                    className="cr-stat-pill"
                    style={{
                      backgroundColor: 'rgba(16, 124, 65, 0.2)',
                      color: 'var(--excel-green-light)',
                      borderColor: 'rgba(16, 124, 65, 0.4)',
                      fontWeight: 600
                    }}
                  >
                    ✨ Sin Duplicados (DISTINCT)
                  </span>
                )}
              </div>

              <div className="cr-results-actions">
                {/* Botón para Eliminar Duplicados del reporte */}
                <button
                  className={`excel-btn ${isDistinct ? 'primary' : ''}`}
                  onClick={handleToggleDistinct}
                  disabled={isExecuting}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: '6px',
                    backgroundColor: isDistinct ? 'var(--excel-green)' : 'var(--excel-bg-app)',
                    borderColor: isDistinct ? 'var(--excel-green)' : 'var(--excel-border)',
                    color: isDistinct ? '#ffffff' : 'var(--text-primary)',
                    fontWeight: isDistinct ? 600 : 500
                  }}
                  title={isDistinct ? 'Filtrado de duplicados activo. Haz clic para restaurar todas las filas.' : 'Eliminar todas las filas duplicadas de este reporte usando SQL DISTINCT.'}
                >
                  <Sparkles size={14} style={{ color: isDistinct ? '#ffffff' : '#ffd54f' }} />
                  <span>{isDistinct ? '✓ Duplicados Eliminados' : '🧹 Eliminar Duplicados'}</span>
                </button>

                <button
                  className="excel-btn"
                  onClick={() => setShowSqlViewer(!showSqlViewer)}
                  title="Ver la consulta SQL Server generada"
                >
                  <Code2 size={14} />
                  <span>{showSqlViewer ? 'Ocultar SQL' : 'Ver SQL'}</span>
                </button>

                <button
                  className="excel-btn"
                  onClick={handleExportCsv}
                  disabled={reportData.length === 0}
                  title="Exportar la vista de página actual en formato CSV"
                >
                  <Download size={14} />
                  <span>Exportar Vista (CSV)</span>
                </button>

                <button
                  className="excel-btn primary"
                  onClick={handleExportExcel}
                  disabled={isExportingExcel || reportData.length === 0}
                  style={{ backgroundColor: 'var(--excel-green)', borderColor: 'var(--excel-green)' }}
                  title="Exportar todas las filas del reporte a un archivo Excel (.xlsx)"
                >
                  <Download size={14} className={isExportingExcel ? 'animate-spin' : ''} />
                  <span>{isExportingExcel ? 'Generando .xlsx...' : 'Descargar Excel (.xlsx)'}</span>
                </button>
              </div>
            </div>

            {/* Visor de Consulta SQL */}
            {showSqlViewer && generatedSql && (
              <div className="cr-sql-viewer">
                <div className="cr-sql-header">
                  <span>Consulta SQL Server generada (Auditable):</span>
                  <button
                    className="excel-btn"
                    style={{ fontSize: '11px', padding: '2px 8px' }}
                    onClick={() => {
                      navigator.clipboard.writeText(generatedSql);
                      setCopiedSql(true);
                      setTimeout(() => setCopiedSql(false), 2000);
                    }}
                  >
                    {copiedSql ? <Check size={12} /> : <Copy size={12} />}
                    <span>{copiedSql ? 'Copiado' : 'Copiar SQL'}</span>
                  </button>
                </div>
                <pre className="cr-sql-code">{generatedSql}</pre>
              </div>
            )}

            {/* Grilla de Resultados */}
            <div className="cr-spreadsheet-container">
              {reportData.length === 0 ? (
                <div className="cr-empty-results">
                  <FileSpreadsheet size={36} style={{ color: 'var(--text-muted)' }} />
                  <p>No hay datos disponibles para mostrar.</p>
                  <button
                    className="excel-btn primary"
                    onClick={() => handleExecuteQuery(1, limit)}
                    style={{ marginTop: '12px' }}
                  >
                    <Play size={14} />
                    <span>Ejecutar Consulta</span>
                  </button>
                </div>
              ) : (
                <div className="cr-table-scroll-wrapper">
                  <table className="cr-data-table">
                    <thead>
                      <tr>
                        <th className="cr-th-row-num">#</th>
                        {reportResultColumns.map(col => (
                          <th key={col.label} title={`${col.tableAlias}.${col.column}`}>
                            {col.label}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {reportData.map((row, rIdx) => (
                        <tr key={rIdx}>
                          <td className="cr-td-row-num">{(page - 1) * limit + rIdx + 1}</td>
                          {reportResultColumns.map(col => {
                            const val = row[col.label];
                            const isNull = val === null || val === undefined;
                            return (
                              <td key={col.label} className={isNull ? 'cr-cell-null' : ''}>
                                {isNull ? <span className="null-tag">NULL</span> : String(val)}
                              </td>
                            );
                          })}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>

            {/* Paginación Inferior */}
            <div className="cr-pagination-bar">
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>Mostrar:</span>
                <select
                  className="excel-select"
                  value={limit}
                  onChange={(e) => {
                    const newLimit = Number(e.target.value);
                    setLimit(newLimit);
                    handleExecuteQuery(1, newLimit);
                  }}
                >
                  <option value={15}>15 filas</option>
                  <option value={30}>30 filas</option>
                  <option value={50}>50 filas</option>
                  <option value={100}>100 filas</option>
                </select>
              </div>

              <div className="cr-pagination-range">
                <span>
                  Mostrando registros <strong>{totalRows === 0 ? 0 : ((page - 1) * limit) + 1}</strong> - <strong>{Math.min(page * limit, totalRows)}</strong> de un total de <strong style={{ color: 'var(--excel-green-light)' }}>{totalRows.toLocaleString()}</strong> filas
                </span>
              </div>

              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <button
                  className="excel-btn"
                  onClick={() => handleExecuteQuery(page - 1, limit)}
                  disabled={page <= 1 || isExecuting}
                >
                  <ChevronLeft size={16} />
                  <span>Anterior</span>
                </button>
                <span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>
                  Pág. <strong>{page}</strong> de <strong>{totalPages || 1}</strong>
                </span>
                <button
                  className="excel-btn"
                  onClick={() => handleExecuteQuery(page + 1, limit)}
                  disabled={page >= totalPages || isExecuting}
                >
                  <span>Siguiente</span>
                  <ChevronRight size={16} />
                </button>
              </div>
            </div>
          </div>
        )}
      </div>

      {/* Modal: Guardar Plantilla */}
      {showSaveModal && (
        <div className="modal-backdrop" onClick={() => setShowSaveModal(false)}>
          <div className="modal-card" onClick={(e) => e.stopPropagation()} style={{ maxWidth: '460px' }}>
            <div className="modal-header">
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <Save size={18} style={{ color: 'var(--excel-green-light)' }} />
                <h3>Guardar Plantilla de Reporte</h3>
              </div>
              <button className="modal-close-btn" onClick={() => setShowSaveModal(false)}><X size={16} /></button>
            </div>
            <div className="modal-body" style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
              <div>
                <label className="cr-label"><strong>Nombre del Reporte:</strong></label>
                <input
                  type="text"
                  className="excel-input"
                  style={{ width: '100%', marginTop: '4px' }}
                  value={tempReportName}
                  onChange={(e) => setTempReportName(e.target.value)}
                  placeholder="Ej. Ventas por Cliente y Ciudad"
                />
              </div>
              <div>
                <label className="cr-label"><strong>Descripción (Opcional):</strong></label>
                <textarea
                  className="excel-input"
                  style={{ width: '100%', marginTop: '4px', minHeight: '70px', resize: 'vertical' }}
                  value={tempReportDesc}
                  onChange={(e) => setTempReportDesc(e.target.value)}
                  placeholder="Descripción de qué tablas cruza y qué información entrega..."
                />
              </div>
            </div>
            <div className="modal-footer">
              <button className="excel-btn" onClick={() => setShowSaveModal(false)}>Cancelar</button>
              <button
                className="excel-btn primary"
                onClick={handleSaveTemplate}
                style={{ backgroundColor: 'var(--excel-green)', borderColor: 'var(--excel-green)' }}
              >
                Guardar en BD
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Modal: Mis Plantillas */}
      {showTemplatesModal && (
        <div className="modal-backdrop" onClick={() => setShowTemplatesModal(false)}>
          <div className="modal-card" onClick={(e) => e.stopPropagation()} style={{ maxWidth: '650px' }}>
            <div className="modal-header">
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <FolderOpen size={18} style={{ color: 'var(--excel-green-light)' }} />
                <h3>Plantillas de Reportes Guardadas</h3>
              </div>
              <button className="modal-close-btn" onClick={() => setShowTemplatesModal(false)}><X size={16} /></button>
            </div>
            <div className="modal-body" style={{ maxHeight: '400px', overflowY: 'auto' }}>
              {templates.length === 0 ? (
                <div style={{ textAlign: 'center', padding: '30px', color: 'var(--text-muted)' }}>
                  No hay plantillas guardadas aún. Crea un reporte y guárdalo para reutilizarlo aquí.
                </div>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
                  {templates.map(tpl => (
                    <div
                      key={tpl.id}
                      className="cr-template-item"
                      onClick={() => handleLoadTemplate(tpl)}
                    >
                      <div style={{ flexGrow: 1 }}>
                        <h4 style={{ fontSize: '13px', color: 'var(--text-primary)', margin: 0 }}>{tpl.name}</h4>
                        {tpl.description && (
                          <p style={{ fontSize: '11.5px', color: 'var(--text-secondary)', margin: '3px 0 0 0' }}>{tpl.description}</p>
                        )}
                        <span style={{ fontSize: '10.5px', color: 'var(--text-muted)', marginTop: '4px', display: 'inline-block' }}>
                          Actualizado: {tpl.updatedAt ? new Date(tpl.updatedAt).toLocaleString() : 'N/A'}
                        </span>
                      </div>
                      <div style={{ display: 'flex', gap: '6px', alignItems: 'center' }}>
                        <button
                          className="excel-btn primary"
                          style={{ fontSize: '11px', padding: '3px 10px' }}
                          onClick={(e) => {
                            e.stopPropagation();
                            handleLoadTemplate(tpl);
                          }}
                        >
                          Cargar
                        </button>
                        <button
                          className="cr-icon-btn danger"
                          onClick={(e) => handleDeleteTemplate(tpl.id, e)}
                          title="Eliminar plantilla"
                        >
                          <Trash2 size={13} />
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
            <div className="modal-footer">
              <button className="excel-btn" onClick={() => setShowTemplatesModal(false)}>Cerrar</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

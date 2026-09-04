import { useState, useEffect, useCallback, useMemo } from 'react';
import {
  Play, Save, FolderOpen, Plus,
  Layers, Settings2, Eye, X, AlertCircle, Sparkles
} from 'lucide-react';
import JoinBuilderPanel from './custom-reports/JoinBuilderPanel';
import ColumnSelectorPanel from './custom-reports/ColumnSelectorPanel';
import FilterSortPanel from './custom-reports/FilterSortPanel';
import ReportResultsGrid from './custom-reports/ReportResultsGrid';
import SaveTemplateModal from './custom-reports/SaveTemplateModal';
import TemplatesModal from './custom-reports/TemplatesModal';
import { useToast } from '../context/ToastContext';
import { useAuth } from '../context/AuthContext';
import { sanitizeForSpreadsheet } from '../utils/csv';

// Orquestador del módulo "Reportes Personalizados": mantiene todo el estado y los
// handlers, y delega la presentación a los paneles/modales de ./custom-reports.
// Cada panel está envuelto en React.memo, por lo que los handlers que reciben se
// pasan memoizados con useCallback para que editar un filtro, por ejemplo, no
// fuerce el re-render del panel de columnas ni de la grilla de resultados.
export default function CustomReports({
  tables = [],
  columnsCache = {},
  setColumnsCache = () => {},
  uxMode = 'simple'
}) {
  const { success, error: toastError, warning: toastWarning, info: toastInfo, confirm } = useToast();
  const { apiFetch } = useAuth();
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
      const res = await apiFetch(`/api/db/tables/${encodeURIComponent(schema)}/${encodeURIComponent(name)}/columns`);
      if (res && res.ok) {
        const cols = await res.json();
        setColumnsCache(prev => ({ ...prev, [key]: cols }));
        return cols;
      }
    } catch (err) {
      console.error('Error fetching columns for', key, err);
    }
    return [];
  }, [columnsCache, setColumnsCache, apiFetch]);

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
      const res = await apiFetch('/api/db/custom-reports/templates');
      if (res && res.ok) {
        const data = await res.json();
        setTemplates(data);
      }
    } catch (e) {
      console.error('Error cargando plantillas', e);
    }
  }, [apiFetch]);

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
      const perTableSuggestions = await Promise.all(currentTables.map(async (t) => {
        const res = await apiFetch(`/api/db/custom-reports/suggest-joins?schema=${encodeURIComponent(t.schema)}&table=${encodeURIComponent(t.name)}`);
        if (!res || !res.ok) return [];
        const list = await res.json();
        return list.map(item => ({
          ...item,
          originTableAlias: t.alias,
          originTableName: t.name,
          originSchema: t.schema
        }));
      }));
      const allSuggestions = perTableSuggestions.flat();
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
  }, [apiFetch]);

  // Seleccionar tabla base inicial
  const handleSelectBaseTable = useCallback((tableKey) => {
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
  }, [loadJoinSuggestionsForTables, fetchColumnsForTable]);

  // Agregar un cruce (Join) sugerido automáticamente por FK
  const handleApplySuggestedJoin = useCallback(async (sug) => {
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
  }, [participatingTables, baseTable, getNextAlias, loadJoinSuggestionsForTables, fetchColumnsForTable]);

  // Agregar un cruce manual
  const handleAddManualJoin = useCallback(() => {
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
  }, [tables, baseTable, participatingTables, getNextAlias, fetchColumnsForTable]);

  // Actualizar un Join
  const handleUpdateJoin = useCallback((id, field, value) => {
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
  }, [fetchColumnsForTable]);

  // Eliminar un Join
  const handleDeleteJoin = useCallback((id) => {
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
  }, [joins, baseTable, loadJoinSuggestionsForTables]);

  // Toggle selección de columna
  const handleToggleColumn = useCallback((tableAlias, colName) => {
    setSelectedColumns(prev => {
      const exists = prev.some(c => c.tableAlias === tableAlias && c.column === colName);
      if (exists) {
        return prev.filter(c => !(c.tableAlias === tableAlias && c.column === colName));
      }
      const tableObj = participatingTables.find(t => t.alias === tableAlias);
      const label = tableObj && !tableObj.isBase ? `${tableObj.name} - ${colName}` : colName;
      return [...prev, { tableAlias, column: colName, label }];
    });
  }, [participatingTables]);

  // Marcar todas las columnas de una tabla
  const handleSelectAllColumnsOfTable = useCallback((tableAlias) => {
    const tableObj = participatingTables.find(t => t.alias === tableAlias);
    if (!tableObj) return;
    const cols = columnsCache[`${tableObj.schema}.${tableObj.name}`] || [];
    setSelectedColumns(prev => {
      const otherCols = prev.filter(c => c.tableAlias !== tableAlias);
      const newCols = cols.map(c => ({
        tableAlias,
        column: c.name,
        label: !tableObj.isBase ? `${tableObj.name} - ${c.name}` : c.name
      }));
      return [...otherCols, ...newCols];
    });
  }, [participatingTables, columnsCache]);

  // Desmarcar todas las columnas de una tabla
  const handleDeselectAllColumnsOfTable = useCallback((tableAlias) => {
    setSelectedColumns(prev => prev.filter(c => c.tableAlias !== tableAlias));
  }, []);

  // Cambiar alias/etiqueta de una columna
  const handleUpdateColumnLabel = useCallback((tableAlias, colName, newLabel) => {
    setSelectedColumns(prev => prev.map(c => {
      if (c.tableAlias === tableAlias && c.column === colName) {
        return { ...c, label: newLabel };
      }
      return c;
    }));
  }, []);

  // Agregar Filtro
  const handleAddFilter = useCallback(() => {
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
  }, [participatingTables, columnsCache]);

  // Actualizar Filtro
  const handleUpdateFilter = useCallback((id, field, value) => {
    setFilters(prev => prev.map(f => {
      if (f.id !== id) return f;
      if (field === 'tableAlias') {
        const tableObj = participatingTables.find(t => t.alias === value);
        const cols = tableObj ? (columnsCache[`${tableObj.schema}.${tableObj.name}`] || []) : [];
        return { ...f, tableAlias: value, column: cols.length > 0 ? cols[0].name : '' };
      }
      return { ...f, [field]: value };
    }));
  }, [participatingTables, columnsCache]);

  // Eliminar Filtro
  const handleDeleteFilter = useCallback((id) => {
    setFilters(prev => prev.filter(f => f.id !== id));
  }, []);

  // Agregar Ordenamiento
  const handleAddSort = useCallback(() => {
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
  }, [participatingTables, columnsCache]);

  // Actualizar Ordenamiento
  const handleUpdateSort = useCallback((id, field, value) => {
    setSorts(prev => prev.map(s => {
      if (s.id !== id) return s;
      if (field === 'tableAlias') {
        const tableObj = participatingTables.find(t => t.alias === value);
        const cols = tableObj ? (columnsCache[`${tableObj.schema}.${tableObj.name}`] || []) : [];
        return { ...s, tableAlias: value, column: cols.length > 0 ? cols[0].name : '' };
      }
      return { ...s, [field]: value };
    }));
  }, [participatingTables, columnsCache]);

  // Eliminar Ordenamiento
  const handleDeleteSort = useCallback((id) => {
    setSorts(prev => prev.filter(s => s.id !== id));
  }, []);

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
  const handleExecuteQuery = useCallback(async (targetPage = 1, targetLimit = limit, targetDistinct = isDistinct) => {
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
      const res = await apiFetch('/api/db/custom-reports/preview', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(query)
      });

      if (!res || !res.ok) {
        const errJson = res ? await res.json().catch(() => ({})) : {};
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
  }, [baseTable, selectedColumns, buildQueryPayload, limit, isDistinct, apiFetch]);

  // Alternar eliminación de duplicados (DISTINCT)
  const handleToggleDistinct = useCallback(() => {
    const nextDistinct = !isDistinct;
    setIsDistinct(nextDistinct);
    handleExecuteQuery(1, limit, nextDistinct);
  }, [isDistinct, handleExecuteQuery, limit]);

  // Exportar a Excel (.xlsx)
  const handleExportExcel = useCallback(async () => {
    if (!baseTable || selectedColumns.length === 0) return;
    setIsExportingExcel(true);
    const query = buildQueryPayload(1, 1000000, isDistinct); // Sin límite para exportación

    try {
      const res = await apiFetch('/api/db/custom-reports/export', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(query)
      });

      if (!res || !res.ok) {
        const err = res ? await res.json().catch(() => ({})) : {};
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
      success('Archivo Excel descargado correctamente');
    } catch (err) {
      toastError(`Error al exportar: ${err.message}`);
    } finally {
      setIsExportingExcel(false);
    }
  }, [baseTable, selectedColumns, buildQueryPayload, isDistinct, reportName, success, toastError, apiFetch]);

  // Exportar vista actual a CSV
  const handleExportCsv = useCallback(() => {
    if (!reportData || reportData.length === 0) return;
    const cols = reportResultColumns.map(c => c.label);
    let csv = cols.map(c => `"${c.replace(/"/g, '""')}"`).join(',') + '\n';

    reportData.forEach(row => {
      const rowVals = cols.map(colName => {
        const val = row[colName];
        if (val === null || val === undefined) return '""';
        const sanitized = sanitizeForSpreadsheet(val);
        return `"${String(sanitized).replace(/"/g, '""')}"`;
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
    success('Vista exportada a CSV');
  }, [reportData, reportResultColumns, reportName, page, success]);

  // Guardar plantilla
  const handleSaveTemplate = useCallback(async () => {
    if (!tempReportName.trim()) {
      toastWarning('El nombre del reporte es obligatorio');
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

      const res = await apiFetch('/api/db/custom-reports/templates', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });

      if (!res || !res.ok) throw new Error('Error al guardar la plantilla.');
      const saved = await res.json();
      setReportId(saved.id);
      setReportName(saved.name);
      setReportDescription(saved.description);
      setShowSaveModal(false);
      loadTemplates();
      success('¡Plantilla de reporte guardada con éxito en la base de datos!');
    } catch (err) {
      toastError(err.message);
    }
  }, [tempReportName, tempReportDesc, baseTable, joins, selectedColumns, filters, sorts, isDistinct, reportId, loadTemplates, success, toastWarning, toastError, apiFetch]);

  // Cargar una plantilla
  const handleLoadTemplate = useCallback((tpl) => {
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
      success(`Plantilla "${tpl.name}" cargada correctamente`);
    } catch (e) {
      toastError('Error al leer el archivo de configuración de la plantilla.');
    }
  }, [loadJoinSuggestionsForTables, success, toastError]);

  // Eliminar plantilla
  const handleDeleteTemplate = useCallback((id, e) => {
    e.stopPropagation();
    confirm({
      title: '¿Eliminar plantilla?',
      message: '¿Estás seguro de que deseas eliminar permanentemente esta plantilla de reporte?',
      confirmText: 'Eliminar',
      cancelText: 'Cancelar',
      type: 'danger',
      onConfirm: async () => {
        try {
          const res = await apiFetch(`/api/db/custom-reports/templates/${encodeURIComponent(id)}`, {
            method: 'DELETE'
          });
          if (res && res.ok) {
            setTemplates(prev => prev.filter(t => t.id !== id));
            setReportId(prevId => (prevId === id ? null : prevId));
            success('Plantilla eliminada correctamente');
          }
        } catch (err) {
          toastError('Error al eliminar la plantilla.');
        }
      }
    });
  }, [confirm, success, toastError, apiFetch]);

  // Reset / Nuevo Reporte
  const handleNewReport = useCallback(() => {
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
  }, []);

  const handleOpenSaveModal = useCallback(() => {
    setTempReportName(reportName);
    setTempReportDesc(reportDescription);
    setShowSaveModal(true);
  }, [reportName, reportDescription]);

  const handleOpenTemplatesModal = useCallback(() => {
    loadTemplates();
    setShowTemplatesModal(true);
  }, [loadTemplates]);

  const handleExecuteFromToolbar = useCallback(() => {
    handleExecuteQuery(1, limit);
  }, [handleExecuteQuery, limit]);

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
            onClick={handleOpenSaveModal}
            title="Guardar esta consulta como plantilla reutilizable"
          >
            <Save size={14} style={{ color: 'var(--excel-green-light)' }} />
            <span>Guardar Plantilla</span>
          </button>

          <button
            className="excel-btn"
            onClick={handleOpenTemplatesModal}
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
            onClick={handleExecuteFromToolbar}
            disabled={isExecuting || !baseTable || selectedColumns.length === 0}
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
        {uxMode === 'simple' && activeStep === 'builder' && (
          <div className="ux-friendly-banner" style={{ margin: '8px 14px 0 14px' }}>
            <div className="ux-friendly-banner-text">
              <Sparkles size={16} className="ux-friendly-banner-icon" />
              <span>
                <strong>Asistente Fácil:</strong> 1. Elige tu tabla de partida • 2. Cruza con otras tablas para traer más datos • 3. Marca qué datos quieres ver en tu Excel.
              </span>
            </div>
          </div>
        )}

        {activeStep === 'builder' ? (
          <div className="cr-builder-layout">
            <JoinBuilderPanel
              tables={tables}
              baseTable={baseTable}
              joins={joins}
              suggestedJoins={suggestedJoins}
              isLoadingSuggestions={isLoadingSuggestions}
              columnsCache={columnsCache}
              onSelectBaseTable={handleSelectBaseTable}
              onAddManualJoin={handleAddManualJoin}
              onApplySuggestedJoin={handleApplySuggestedJoin}
              onUpdateJoin={handleUpdateJoin}
              onDeleteJoin={handleDeleteJoin}
              uxMode={uxMode}
            />

            <ColumnSelectorPanel
              participatingTables={participatingTables}
              selectedColumns={selectedColumns}
              columnsCache={columnsCache}
              activeColumnTable={activeColumnTable}
              setActiveColumnTable={setActiveColumnTable}
              columnSearchQuery={columnSearchQuery}
              setColumnSearchQuery={setColumnSearchQuery}
              onSelectAllColumnsOfTable={handleSelectAllColumnsOfTable}
              onDeselectAllColumnsOfTable={handleDeselectAllColumnsOfTable}
              onToggleColumn={handleToggleColumn}
              onUpdateColumnLabel={handleUpdateColumnLabel}
              uxMode={uxMode}
            />

            <FilterSortPanel
              participatingTables={participatingTables}
              filters={filters}
              sorts={sorts}
              columnsCache={columnsCache}
              onAddFilter={handleAddFilter}
              onUpdateFilter={handleUpdateFilter}
              onDeleteFilter={handleDeleteFilter}
              onAddSort={handleAddSort}
              onUpdateSort={handleUpdateSort}
              onDeleteSort={handleDeleteSort}
              uxMode={uxMode}
            />
          </div>
        ) : (
          <ReportResultsGrid
            totalRows={totalRows}
            executionTime={executionTime}
            page={page}
            totalPages={totalPages}
            isDistinct={isDistinct}
            onToggleDistinct={handleToggleDistinct}
            isExecuting={isExecuting}
            showSqlViewer={showSqlViewer}
            setShowSqlViewer={setShowSqlViewer}
            onExportCsv={handleExportCsv}
            isExportingExcel={isExportingExcel}
            onExportExcel={handleExportExcel}
            generatedSql={generatedSql}
            copiedSql={copiedSql}
            setCopiedSql={setCopiedSql}
            reportData={reportData}
            reportResultColumns={reportResultColumns}
            limit={limit}
            setLimit={setLimit}
            onExecuteQuery={handleExecuteQuery}
            uxMode={uxMode}
          />
        )}
      </div>

      <SaveTemplateModal
        show={showSaveModal}
        onClose={() => setShowSaveModal(false)}
        tempReportName={tempReportName}
        setTempReportName={setTempReportName}
        tempReportDesc={tempReportDesc}
        setTempReportDesc={setTempReportDesc}
        onSave={handleSaveTemplate}
      />

      <TemplatesModal
        show={showTemplatesModal}
        onClose={() => setShowTemplatesModal(false)}
        templates={templates}
        onLoadTemplate={handleLoadTemplate}
        onDeleteTemplate={handleDeleteTemplate}
      />
    </div>
  );
}

import React, { useState } from 'react';
import { FileSpreadsheet, Search, ChevronLeft, ChevronRight } from 'lucide-react';

export default function SheetTabs({ 
  tables, 
  activeTable, 
  onSelectTable 
}) {
  const [searchQuery, setSearchQuery] = useState('');

  const filteredTables = tables.filter(t => 
    t.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
    t.schema.toLowerCase().includes(searchQuery.toLowerCase())
  );

  return (
    <div className="worksheet-tabs-bar">
      {/* Controles de Navegación de Pestañas (Estilo Excel) */}
      <div className="sheet-nav">
        <button 
          className="excel-btn sheet-nav-btn"
          title="Primera pestaña"
          aria-label="Ir a la primera hoja"
          disabled={filteredTables.length === 0}
          onClick={() => filteredTables.length > 0 && onSelectTable(filteredTables[0])}
        >
          <ChevronLeft size={14} strokeWidth={3} />
        </button>
        <button 
          className="excel-btn sheet-nav-btn"
          title="Última pestaña"
          aria-label="Ir a la última hoja"
          disabled={filteredTables.length === 0}
          onClick={() => filteredTables.length > 0 && onSelectTable(filteredTables[filteredTables.length - 1])}
        >
          <ChevronRight size={14} strokeWidth={3} />
        </button>
      </div>

      {/* Listado de Hojas (Tablas) */}
      <div className="tabs-wrapper">
        {filteredTables.length === 0 ? (
          <span className="sheet-tabs-empty">
            No se encontraron tablas
          </span>
        ) : (
          filteredTables.map((table) => {
            const isActive = activeTable && activeTable.schema === table.schema && activeTable.name === table.name;
            return (
              <button
                key={`${table.schema}.${table.name}`}
                className={`sheet-tab ${isActive ? 'active' : ''}`}
                onClick={() => onSelectTable(table)}
                aria-current={isActive ? 'true' : undefined}
                title={`${table.schema}.${table.name}`}
              >
                <FileSpreadsheet className="sheet-icon" />
                <span>
                  {table.schema === 'dbo' ? table.name : `${table.schema}.${table.name}`}
                </span>
              </button>
            );
          })
        )}
      </div>

      {/* Buscador de "Hojas" integrado */}
      <div className="sheet-search-box">
        <Search 
          size={12} 
          className="sheet-search-icon"
        />
        <input 
          type="text" 
          placeholder="Buscar hoja..."
          aria-label="Buscar hoja o tabla"
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          className="sheet-search-input"
        />
      </div>
    </div>
  );
}

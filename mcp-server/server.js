#!/usr/bin/env node

/**
 * JetBrains Inspection API MCP Server
 * Provides MCP tools for accessing comprehensive inspection results
 * using the full JetBrains inspection framework (mirrors IDE "Inspect Code")
 */

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import http from "http";

const IDE_PORT = process.env.IDE_PORT || '63341';
const BASE_URL = `http://localhost:${IDE_PORT}/api/inspection`;

// Create the MCP server
const server = new McpServer(
  {
    name: 'jetbrains-inspection-mcp',
    version: '1.10.13'
  },
  {
    capabilities: {
      tools: {}
    }
  }
);

// Helper function for HTTP requests
function httpGet(url) {
  return new Promise((resolve, reject) => {
    const req = http.get(url, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        try {
          const result = JSON.parse(data);
          resolve(result);
        } catch (error) {
          reject(new Error(`Invalid JSON response: ${error.message}`));
        }
      });
    });

    req.on('error', (error) => {
      const parts = [];
      if (error && error.message) parts.push(error.message);
      if (error && error.code) parts.push(`code=${error.code}`);
      if (error && typeof error.port !== 'undefined') parts.push(`port=${error.port}`);
      if (error && error.address) parts.push(`addr=${error.address}`);

      let hint = '';
      const errorCode = error && typeof error.code === 'string' ? error.code : undefined;
      if (errorCode === 'ECONNREFUSED') {
        hint = `Ensure JetBrains IDE is running, plugin installed, and built-in server enabled on port ${IDE_PORT} (Allow unsigned requests).`;
      }
      const msg = `HTTP request failed: ${parts.join(' | ')}${hint ? ' | ' + hint : ''}`;
      reject(new Error(msg));
    });

    req.setTimeout(10000, () => {
      req.destroy();
      reject(new Error(`Request timeout | Ensure IDE on port ${IDE_PORT} is reachable`));
    });
  });
}


// Tool: Get inspection problems
server.tool(
  "inspection_get_problems",
  "List inspection problems (scoped, filterable). Use inspection_get_status first and only call when is_scanning=false and has_inspection_results=true.",
  {
    project: z.string().optional().describe("Name of the project to inspect (e.g., 'odoo-ai', 'MyProject'). If not specified, inspects the currently focused project"),
    scope: z.string().optional().default("whole_project").describe("Inspection scope: 'whole_project', 'current_file', or custom scope name (e.g., 'odoo_intelligence_mcp') to filter by file path"),
    severity: z.string().optional().default("all").describe("Severity filter: 'error', 'warning', 'weak_warning', 'info', 'grammar', 'typo', or 'all'"),
    problem_type: z.string().optional().describe("Filter by inspection type (e.g., 'PyUnresolvedReferencesInspection', 'SpellCheck', 'Unused') - matches against inspection type or category"),
    file_pattern: z.string().optional().describe("Filter by file path pattern - can be a simple string match or regex (e.g., '*.py', 'src/.*\\.js$', 'test')"),
    limit: z.number().optional().default(100).describe("Maximum number of problems to return (default: 100)"),
    offset: z.number().optional().default(0).describe("Number of problems to skip for pagination (default: 0)")
  },
  async ({ project, scope, severity, problem_type, file_pattern, limit, offset }) => {
    try {
      const params = new URLSearchParams();
      if (project) params.append("project", project);
      // Always include scope and severity explicitly for clarity and consistency
      params.append("scope", scope || "whole_project");
      params.append("severity", severity || "all");
      if (problem_type) params.append("problem_type", problem_type);
      if (file_pattern) params.append("file_pattern", file_pattern);
      if (limit !== 100) params.append("limit", limit.toString());
      if (offset !== 0) params.append("offset", offset.toString());
      
      const url = `${BASE_URL}/problems${params.toString() ? '?' + params.toString() : ''}`;
      /** @type {{status: string, total_problems: number, problems_shown?: number, pagination?: {has_more: boolean, next_offset: number}}} */
      const result = await httpGet(url);
      
      // Add guidance based on response
      let guidance = result.status === "no_results" 
        ? "\n\n⚠️  No results found. Either trigger an inspection first, or the codebase is clean (100% pass rate = no inspection window created)."
        : result.total_problems === 0
        ? "\n\n✅ No problems found matching filters - codebase is clean!"
        : `\n\n📊 Found ${result.total_problems} problems total, showing ${result.problems_shown !== undefined ? result.problems_shown : result.total_problems}.`;
      
      if (result.pagination?.has_more) {
        guidance += `\n\n➡️  More results available. Use offset=${result.pagination.next_offset} to get the next page.`;
      }
      
      return {
        content: [{
          type: "text",
          text: JSON.stringify(result, null, 2) + guidance
        }]
      };
    } catch (error) {
      return {
        content: [{
          type: "text",
          text: `Error getting problems: ${error.message}`
        }]
      };
    }
  }
);

// Tool: Trigger inspection
server.tool(
  "inspection_trigger",
  "Trigger an inspection run. Scopes: whole_project|current_file|directory|changed_files|files (see parameters).",
  {
    project: z.string().optional().describe("Project name (optional)"),
    scope: z.enum(["whole_project", "current_file", "directory", "changed_files", "files"]).optional().describe("Analysis scope"),
    // directory scope
    dir: z.string().optional().describe("Directory when scope=directory"),
    directory: z.string().optional().describe("Alias for dir"),
    path: z.string().optional().describe("Alias for dir"),
    // files scope
    files: z.array(z.string()).optional().describe("File paths when scope=files"),
    // changed_files scope
    include_unversioned: z.boolean().optional().describe("Include unversioned (default true)"),
    changed_files_mode: z.enum(["all", "staged", "unstaged"]).optional().describe("Best-effort Git filter: 'staged' or 'unstaged'"),
    max_files: z.number().int().positive().optional().describe("Limit files for speed"),
    // profile
    profile: z.string().optional().describe("Inspection profile name")
  },
  async ({ project, scope, dir, directory, path, files, include_unversioned, changed_files_mode, max_files, profile }) => {
    try {
      const params = new URLSearchParams();
      if (project) params.append("project", project);
      if (scope) params.append("scope", scope);
      const chosenDir = dir || directory || path;
      if (chosenDir) params.append("dir", chosenDir);
      if (files && files.length) {
        for (const f of files) params.append("file", f);
      }
      if (include_unversioned !== undefined) params.append("include_unversioned", include_unversioned ? "true" : "false");
      if (changed_files_mode) params.append("changed_files_mode", changed_files_mode);
      if (typeof max_files === 'number') params.append("max_files", String(max_files));
      if (profile) params.append("profile", profile);
      const url = `${BASE_URL}/trigger${params.toString() ? '?' + params.toString() : ''}`;
      const result = await httpGet(url);
      
      return {
        content: [{
          type: "text",
          text: JSON.stringify(result, null, 2) + "\n\nUse inspection_get_status to wait before fetching problems."
        }]
      };
    } catch (error) {
      return {
        content: [{
          type: "text",
          text: `Error triggering inspection: ${error.message}`
        }]
      };
    }
  }
);

// Tool: Get inspection status
server.tool(
  "inspection_get_status", 
  "Get inspection status (is_scanning, has_inspection_results). Wait until is_scanning=false before calling inspection_get_problems.",
  {
    project: z.string().optional().describe("Name of the project to check status for (e.g., 'odoo-ai', 'MyProject'). If not specified, checks status for the currently focused project")
  },
  async ({ project }) => {
    try {
      const params = new URLSearchParams();
      if (project) params.append("project", project);
      const url = `${BASE_URL}/status${params.toString() ? '?' + params.toString() : ''}`;
      /** @type {{is_scanning: boolean, has_inspection_results: boolean, clean_inspection?: boolean}} */
      const result = await httpGet(url);
      
      const statusInfo = result.is_scanning ? 
        "\n\n⏳ Inspection still running - wait before getting problems" :
        result.clean_inspection ? 
          "\n\n✅ Inspection complete - codebase is clean (no problems found)" :
        result.has_inspection_results ? 
          "\n\n✅ Inspection complete - problems found, ready to retrieve" :
          "\n\n❌ No recent inspection - trigger inspection first";
      
      return {
        content: [{
          type: "text", 
          text: JSON.stringify(result, null, 2) + statusInfo
        }]
      };
    } catch (error) {
      return {
        content: [{
          type: "text",
          text: `Error getting status: ${error.message}`
        }]
      };
    }
  }
);

// Main function to start the server
async function main() {
  try {
    console.error('[DEBUG] Starting Inspection MCP Server...');
    
    // Create stdio transport
    const transport = new StdioServerTransport();
    
    // Connect the server to the transport
    await server.connect(transport);
    
    console.error('[DEBUG] Inspection MCP Server started successfully');
    
    process.on('SIGINT', async () => {
      console.error('[DEBUG] Shutting down server...');
      await server.close();
      process.exit(0);
    });
    
    process.on('SIGTERM', async () => {
      console.error('[DEBUG] Shutting down server...');
      await server.close();
      process.exit(0);
    });
    
  } catch (error) {
    console.error('[ERROR] Failed to start server:', error);
    process.exit(1);
  }
}

// Start the server
main().catch((error) => {
  console.error(`FATAL: ${error instanceof Error ? error.message : String(error)}`);
  process.exit(1);
});

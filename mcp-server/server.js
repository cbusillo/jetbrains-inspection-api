#!/usr/bin/env node

/**
 * JetBrains Inspection API MCP Server
 * Provides Claude Code tools for accessing comprehensive inspection results
 * using the full JetBrains inspection framework (mirrors PyCharm's "Inspect Code")
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
    version: '1.10.5'
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
      reject(new Error(`HTTP request failed: ${error.message}`));
    });
    
    req.setTimeout(10000, () => {
      req.destroy();
      reject(new Error('Request timeout'));
    });
  });
}


// Tool: Get inspection problems
server.tool(
  "inspection_get_problems",
  "Get comprehensive inspection problems using JetBrains inspection framework. IMPORTANT: Before calling this, ensure inspection_get_status shows 'is_scanning: false' and 'has_inspection_results: true'. If inspection is still running, wait and check status again.",
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
  "Trigger a full project inspection in the IDE. IMPORTANT: After triggering, you MUST use inspection_get_status to wait for completion before calling inspection_get_problems. The inspection process typically takes 10-30 seconds depending on project size.",
  {
    project: z.string().optional().describe("Name of the project to trigger inspection for (e.g., 'odoo-ai', 'MyProject'). If not specified, triggers for the currently focused project")
  },
  async ({ project }) => {
    try {
      const params = new URLSearchParams();
      if (project) params.append("project", project);
      const url = `${BASE_URL}/trigger${params.toString() ? '?' + params.toString() : ''}`;
      const result = await httpGet(url);
      
      return {
        content: [{
          type: "text",
          text: JSON.stringify(result, null, 2) + "\n\n⚠️  IMPORTANT: Use inspection_get_status to check when inspection completes before getting problems!"
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
  "Get the current inspection status and check if results are available. Check 'is_scanning' field - if true, inspection is still running and you should wait. Only call inspection_get_problems when 'is_scanning' is false and 'has_inspection_results' is true.",
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
#!/usr/bin/env node

/**
 * JetBrains Inspection API MCP Server
 * Provides Claude Code tools for accessing inspection results without curl permissions
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
    version: '1.6.0'
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
  "Get inspection problems and status",
  {
    scope: z.string().optional().default("whole_project").describe("Inspection scope: 'whole_project' or 'current_file'"),
    severity: z.string().optional().default("warning").describe("Severity filter: 'error', 'warning', 'weak_warning', 'info', or 'all'")
  },
  async ({ scope, severity }) => {
    try {
      const params = new URLSearchParams();
      if (scope !== "whole_project") params.append("scope", scope);
      if (severity !== "warning") params.append("severity", severity);
      
      const url = `${BASE_URL}/problems${params.toString() ? '?' + params.toString() : ''}`;
      const result = await httpGet(url);
      
      return {
        content: [{
          type: "text",
          text: JSON.stringify(result, null, 2)
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

// Tool: Get inspection categories
server.tool(
  "inspection_get_categories",
  "Get inspection problem categories summary",
  {},
  async () => {
    try {
      const url = `${BASE_URL}/inspections`;
      const result = await httpGet(url);
      
      return {
        content: [{
          type: "text",
          text: JSON.stringify(result, null, 2)
        }]
      };
    } catch (error) {
      return {
        content: [{
          type: "text",
          text: `Error getting categories: ${error.message}`
        }]
      };
    }
  }
);

// Tool: Get inspection problems for a specific file
server.tool(
  "inspection_get_file_problems",
  "Get inspection problems for a specific file",
  {
    file_path: z.string().describe("Absolute path to the file to inspect"),
    severity: z.string().optional().default("all").describe("Severity filter: 'error', 'warning', 'weak_warning', 'info', or 'all'")
  },
  async ({ file_path, severity }) => {
    try {
      const params = new URLSearchParams();
      if (severity !== "all") params.append("severity", severity);
      
      const url = `${BASE_URL}/problems/${encodeURIComponent(file_path)}${params.toString() ? '?' + params.toString() : ''}`;
      const result = await httpGet(url);
      
      return {
        content: [{
          type: "text",
          text: JSON.stringify(result, null, 2)
        }]
      };
    } catch (error) {
      return {
        content: [{
          type: "text",
          text: `Error getting file problems: ${error.message}`
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
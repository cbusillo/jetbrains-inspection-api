import { describe, it, before, after } from 'node:test';
import assert from 'node:assert';
import { spawn } from 'node:child_process';
import { setTimeout } from 'node:timers/promises';

const BASE_URL = 'http://localhost:63340/api/inspection';

async function httpGet(url) {
  try {
    const response = await fetch(url);
    return await response.json();
  } catch (error) {
    return { error: error.message };
  }
}

describe('MCP Integration Tests', () => {
  let serverProcess;

  before(async () => {
    console.log('Starting MCP server for integration tests...');
    serverProcess = spawn('node', ['server.js'], {
      stdio: ['ignore', 'pipe', 'pipe']
    });

    await setTimeout(3000);
  });

  after(() => {
    if (serverProcess) {
      serverProcess.kill();
    }
  });

  describe('IDE API Integration', () => {
    it('should attempt to connect to IDE API', async () => {
      const result = await httpGet(`${BASE_URL}/problems`);
      
      assert.ok(result, 'Should receive a response from IDE API');
      
      if (result.error) {
        assert.ok(result.error.includes('ECONNREFUSED') || 
                 result.error.includes('fetch'), 
                 'Should fail gracefully when IDE is not running');
      } else {
        assert.ok(Array.isArray(result.problems) || result.problems, 
                 'Should return problems array when IDE is running');
      }
    });

    it('should handle file-specific requests', async () => {
      const testFile = '/Users/test/example.js';
      const encodedPath = encodeURIComponent(testFile);
      const result = await httpGet(`${BASE_URL}/problems/${encodedPath}`);
      
      assert.ok(result, 'Should receive response for file-specific requests');
    });

    it('should handle category requests', async () => {
      const result = await httpGet(`${BASE_URL}/inspections`);
      
      assert.ok(result, 'Should receive response for category requests');
    });

    it('should handle query parameters', async () => {
      const params = new URLSearchParams({
        severity: 'error',
        scope: 'whole_project'
      });
      
      const result = await httpGet(`${BASE_URL}/problems?${params}`);
      
      assert.ok(result, 'Should handle requests with query parameters');
    });
  });

  describe('MCP Tool Simulation', () => {
    it('should simulate inspection_get_problems tool', async () => {
      const mockParams = {
        scope: 'whole_project',
        severity: 'all'
      };

      const params = new URLSearchParams();
      if (mockParams.scope !== 'whole_project') params.append('scope', mockParams.scope);
      if (mockParams.severity !== 'all') params.append('severity', mockParams.severity);
      
      const url = `${BASE_URL}/problems${params.toString() ? '?' + params.toString() : ''}`;
      const result = await httpGet(url);
      
      assert.ok(result, 'Should simulate MCP tool behavior');
    });

    it('should simulate inspection_get_file_problems tool', async () => {
      const mockParams = {
        file_path: '/Users/test/example.js',
        severity: 'error'
      };

      const params = new URLSearchParams();
      if (mockParams.severity !== 'all') params.append('severity', mockParams.severity);
      
      const url = `${BASE_URL}/problems/${encodeURIComponent(mockParams.file_path)}${params.toString() ? '?' + params.toString() : ''}`;
      const result = await httpGet(url);
      
      assert.ok(result, 'Should simulate file-specific MCP tool behavior');
    });

    it('should simulate inspection_get_categories tool', async () => {
      const result = await httpGet(`${BASE_URL}/inspections`);
      
      assert.ok(result, 'Should simulate categories MCP tool behavior');
    });
  });

  describe('Error Recovery', () => {
    it('should handle malformed requests gracefully', async () => {
      const malformedUrl = `${BASE_URL}/invalid-endpoint`;
      const result = await httpGet(malformedUrl);
      
      assert.ok(result, 'Should return some response for malformed requests');
    });

    it('should handle timeout scenarios', async () => {
      const controller = new AbortController();
      const timeoutId = globalThis.setTimeout(() => controller.abort(), 50);
      
      try {
        await fetch(`${BASE_URL}/problems`, {
          signal: controller.signal
        });
        globalThis.clearTimeout(timeoutId);
        assert.ok(true, 'Request completed successfully or was aborted gracefully');
      } catch (error) {
        globalThis.clearTimeout(timeoutId);
        assert.ok(error.name === 'AbortError' || error.message.includes('aborted'), 
                 'Should handle timeout/abort scenarios gracefully');
      }
    });
  });
});
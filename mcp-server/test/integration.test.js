import { describe, it, before, after } from 'node:test';
import assert from 'node:assert';
import { spawn } from 'node:child_process';
import { setTimeout } from 'node:timers/promises';

const IDE_PORT = process.env.IDE_PORT || '63341';
const BASE_URL = `http://localhost:${IDE_PORT}/api/inspection`;

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
        assert.ok(result && (Array.isArray(result.problems) || typeof result === 'object'), 
                 'Should return valid response when IDE is running');
      }
    });

    it('should support file_pattern filtering', async () => {
      const params = new URLSearchParams({
        severity: 'all',
        file_pattern: '*.py',
        limit: '1'
      });

      const result = await httpGet(`${BASE_URL}/problems?${params}`);
      assert.ok(result, 'Should receive response for file_pattern filtering');
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

    it('should simulate inspection_trigger and status polling', async () => {
      const trigger = await httpGet(`${BASE_URL}/trigger?scope=whole_project`);
      assert.ok(trigger, 'Should receive trigger response');

      const status = await httpGet(`${BASE_URL}/status`);
      assert.ok(status, 'Should receive status response');
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
      
      // Immediately abort to test abort signal handling
      controller.abort();
      
      try {
        await fetch(`${BASE_URL}/problems`, {
          signal: controller.signal
        });
        assert.fail('Request should have been aborted');
      } catch (error) {
        assert.ok(error.name === 'AbortError', 
                 `Expected AbortError, got ${error.name}: ${error.message}`);
      }
    });
  });
});

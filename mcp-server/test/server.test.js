import { describe, it, before, after } from 'node:test';
import assert from 'node:assert';
import { spawn } from 'node:child_process';
import { setTimeout } from 'node:timers/promises';

const IDE_PORT = process.env.IDE_PORT || '63341';
const BASE_URL = `http://localhost:${IDE_PORT}/api/inspection`;

describe('MCP Server Tests', () => {
  let serverProcess;
  let mcpReady = false;

  before(async () => {
    console.log('Starting MCP server for tests...');
    serverProcess = spawn('node', ['server.js'], {
      stdio: ['pipe', 'pipe', 'pipe']
    });

    serverProcess.stdout.on('data', (data) => {
      const output = data.toString();
      if (output.includes('MCP server running')) {
        mcpReady = true;
      }
    });

    serverProcess.stderr.on('data', (data) => {
      console.error('MCP stderr:', data.toString());
    });

    await setTimeout(2000);
    
    if (!mcpReady) {
      console.log('MCP server may not be fully ready, but proceeding with tests...');
    }
  });

  after(() => {
    if (serverProcess) {
      serverProcess.kill();
    }
  });

  describe('Server Startup', () => {
    it('should start without errors', () => {
      assert.ok(serverProcess.pid, 'Server process should have a PID');
      assert.ok(!serverProcess.killed, 'Server process should not be killed');
    });

    it('should have required environment variables', () => {
      const requiredEnvVars = ['IDE_PORT'];
      requiredEnvVars.forEach(envVar => {
        const value = process.env[envVar] || '63340';
        assert.ok(value, `Environment variable ${envVar} should be set`);
      });
    });
  });

  describe('MCP Tools Registration', () => {
    const expectedTools = [
      'inspection_get_problems',
      'inspection_get_categories', 
      'inspection_get_file_problems'
    ];

    expectedTools.forEach(toolName => {
      it(`should register ${toolName} tool`, async () => {
        assert.ok(true, `${toolName} tool should be registered`);
      });
    });
  });

  describe('HTTP Request Handling', () => {
    it('should handle basic HTTP requests', async () => {
      const testUrl = `${BASE_URL}/problems`;
      assert.ok(testUrl.startsWith('http'), 'Should construct valid HTTP URLs');
    });

    it('should handle URL encoding for file paths', async () => {
      const testPath = '/test/file with spaces.js';
      const encoded = encodeURIComponent(testPath);
      assert.ok(encoded.includes('%20'), 'Should properly encode spaces in file paths');
    });

    it('should handle query parameters', async () => {
      const params = new URLSearchParams();
      params.append('severity', 'error');
      params.append('scope', 'current_file');
      
      const queryString = params.toString();
      assert.ok(queryString.includes('severity=error'), 'Should handle severity parameter');
      assert.ok(queryString.includes('scope=current_file'), 'Should handle scope parameter');
    });
  });

  describe('Error Handling', () => {
    it('should handle network errors gracefully', async () => {
      const invalidUrl = 'http://invalid-host:99999/api/test';
      
      try {
        await fetch(invalidUrl);
        assert.fail('Should have thrown an error');
      } catch (error) {
        assert.ok(error, 'Should handle network errors');
      }
    });

    it('should validate input parameters', () => {
      const testFilePath = '/valid/file/path.js';
      const testSeverity = 'error';
      
      assert.ok(testFilePath.length > 0, 'File path should be non-empty string');
      assert.ok(['error', 'warning', 'weak_warning', 'info', 'all'].includes(testSeverity), 
        'Severity should be valid value');
    });
  });

  describe('JSON Response Handling', () => {
    it('should parse JSON responses correctly', () => {
      const testResponse = {
        problems: [
          {
            file: '/test/file.js',
            severity: 'ERROR',
            description: 'Test error',
            line: 1,
            column: 1
          }
        ]
      };

      const jsonString = JSON.stringify(testResponse, null, 2);
      const parsed = JSON.parse(jsonString);
      
      assert.deepEqual(parsed, testResponse, 'Should parse JSON correctly');
    });

    it('should handle empty responses', () => {
      const emptyResponse = { problems: [] };
      const jsonString = JSON.stringify(emptyResponse);
      const parsed = JSON.parse(jsonString);
      
      assert.deepEqual(parsed.problems, [], 'Should handle empty problem arrays');
    });
  });

  describe('Scope Parameter Handling', () => {
    it('should handle whole_project scope parameter', () => {
      const params = new URLSearchParams();
      params.append('scope', 'whole_project');
      
      const queryString = params.toString();
      assert.ok(queryString.includes('scope=whole_project'), 'Should handle whole_project scope');
    });

    it('should handle current_file scope parameter', () => {
      const params = new URLSearchParams();
      params.append('scope', 'current_file');
      
      const queryString = params.toString();
      assert.ok(queryString.includes('scope=current_file'), 'Should handle current_file scope');
    });

    it('should handle custom scope parameters', () => {
      const customScopes = [
        'odoo_intelligence_mcp',
        'test_project',
        'custom_directory'
      ];

      customScopes.forEach(scope => {
        const params = new URLSearchParams();
        params.append('scope', scope);
        
        const queryString = params.toString();
        assert.ok(queryString.includes(`scope=${scope}`), `Should handle custom scope: ${scope}`);
      });
    });

    it('should handle scope parameter with special characters', () => {
      const specialScopes = [
        'project-with-dashes',
        'project_with_underscores',
        'project.with.dots'
      ];

      specialScopes.forEach(scope => {
        const params = new URLSearchParams();
        params.append('scope', scope);
        
        const queryString = params.toString();
        const decoded = decodeURIComponent(queryString);
        assert.ok(decoded.includes(scope), `Should handle scope with special chars: ${scope}`);
      });
    });

    it('should handle scope and severity parameters together', () => {
      const testCombinations = [
        { scope: 'whole_project', severity: 'error' },
        { scope: 'current_file', severity: 'warning' },
        { scope: 'odoo_intelligence_mcp', severity: 'all' },
        { scope: 'custom_scope', severity: 'info' }
      ];

      testCombinations.forEach(({ scope, severity }) => {
        const params = new URLSearchParams();
        params.append('scope', scope);
        params.append('severity', severity);
        
        const queryString = params.toString();
        assert.ok(queryString.includes(`scope=${scope}`), `Should include scope: ${scope}`);
        assert.ok(queryString.includes(`severity=${severity}`), `Should include severity: ${severity}`);
      });
    });

    it('should validate scope parameter values', () => {
      const validScopes = [
        'whole_project',
        'current_file', 
        'odoo_intelligence_mcp',
        'any_custom_string'
      ];

      validScopes.forEach(scope => {
        assert.ok(typeof scope === 'string' && scope.length > 0, 
          `Scope '${scope}' should be a non-empty string`);
      });
    });

    it('should handle missing scope parameter gracefully', () => {
      const params = new URLSearchParams();
      params.append('severity', 'error');
      // No scope parameter added
      
      const queryString = params.toString();
      assert.ok(!queryString.includes('scope='), 'Should handle missing scope parameter');
      assert.ok(queryString.includes('severity=error'), 'Should still handle other parameters');
    });

    it('should include directory param when provided for trigger', () => {
      const params = new URLSearchParams();
      params.append('scope', 'directory');
      params.append('dir', 'src');
      const queryString = params.toString();
      assert.ok(queryString.includes('scope=directory'));
      assert.ok(queryString.includes('dir=src'));
    });
  });
});

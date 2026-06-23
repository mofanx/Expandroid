using System;
using System.Threading;
using System.Threading.Tasks;
using Expandroid.Services;

namespace Expandroid.Tests
{
    /// <summary>
    /// WebDAV compatibility test harness for Nextcloud, 坚果云, Synology NAS, rclone.
    /// Run manually with a real WebDAV server to verify PROPFIND/GET/PUT/MKCOL/DELETE.
    /// </summary>
    public class WebDavCompatTest
    {
        private readonly string _baseUri;
        private readonly string _username;
        private readonly string _password;

        public WebDavCompatTest(string baseUri, string username, string password)
        {
            _baseUri = baseUri;
            _username = username;
            _password = password;
        }

        public async Task<bool> RunAllAsync(CancellationToken ct = default)
        {
            var results = new[]
            {
                ("TestConnection", await TestConnection(ct)),
                ("TestPropFindDepth0", await TestPropFindDepth0(ct)),
                ("TestPropFindDepth1", await TestPropFindDepth1(ct)),
                ("TestMkcol", await TestMkcol(ct)),
                ("TestPutAndGet", await TestPutAndGet(ct)),
                ("TestDelete", await TestDelete(ct)),
                ("TestETag", await TestETag(ct)),
            };

            bool allPassed = true;
            foreach (var (name, passed) in results)
            {
                Console.WriteLine($"  {(passed ? "PASS" : "FAIL")} {name}");
                if (!passed) allPassed = false;
            }
            return allPassed;
        }

        public async Task<bool> TestConnection(CancellationToken ct = default)
        {
            using var client = new WebDavClient(_baseUri, _username, _password);
            return await client.TestConnectionAsync(ct);
        }

        public async Task<bool> TestPropFindDepth0(CancellationToken ct = default)
        {
            using var client = new WebDavClient(_baseUri, _username, _password);
            var info = await client.GetFileInfoAsync("", ct);
            return info != null && info.IsDirectory;
        }

        public async Task<bool> TestPropFindDepth1(CancellationToken ct = default)
        {
            using var client = new WebDavClient(_baseUri, _username, _password);
            var files = await client.ListDirectoryAsync("", ct);
            return files != null;
        }

        public async Task<bool> TestMkcol(CancellationToken ct = default)
        {
            using var client = new WebDavClient(_baseUri, _username, _password);
            var testDir = $"expandroid-test-{DateTime.UtcNow:yyyyMMddHHmmss}";
            var ok = await client.MkcolAsync(testDir, ct);
            if (ok) await client.DeleteFileAsync(testDir, ct);
            return ok;
        }

        public async Task<bool> TestPutAndGet(CancellationToken ct = default)
        {
            using var client = new WebDavClient(_baseUri, _username, _password);
            var testFile = $"expandroid-test-{DateTime.UtcNow:yyyyMMddHHmmss}.yml";
            var testContent = "matches:\n  - trigger: test\n    replace: hello\n";
            var putOk = await client.PutFileAsync(testFile, testContent, ct);
            if (!putOk) return false;
            var got = await client.GetFileAsync(testFile, ct);
            await client.DeleteFileAsync(testFile, ct);
            return got == testContent;
        }

        public async Task<bool> TestDelete(CancellationToken ct = default)
        {
            using var client = new WebDavClient(_baseUri, _username, _password);
            var testFile = $"expandroid-del-test-{DateTime.UtcNow:yyyyMMddHHmmss}.yml";
            await client.PutFileAsync(testFile, "test", ct);
            var delOk = await client.DeleteFileAsync(testFile, ct);
            return delOk;
        }

        public async Task<bool> TestETag(CancellationToken ct = default)
        {
            using var client = new WebDavClient(_baseUri, _username, _password);
            var testFile = $"expandroid-etag-test-{DateTime.UtcNow:yyyyMMddHHmmss}.yml";
            await client.PutFileAsync(testFile, "test", ct);
            var etag1 = await client.GetETagAsync(testFile, ct);
            await client.PutFileAsync(testFile, "test2", ct);
            var etag2 = await client.GetETagAsync(testFile, ct);
            await client.DeleteFileAsync(testFile, ct);
            return !string.IsNullOrEmpty(etag1) && etag1 != etag2;
        }
    }
}

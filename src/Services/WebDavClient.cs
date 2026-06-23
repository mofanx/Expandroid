using System;
using System.Collections.Generic;
using System.Linq;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Xml.Linq;

namespace Expandroid.Services
{
    public class WebDavFileInfo
    {
        public string Href { get; set; }
        public string DisplayName { get; set; }
        public long Size { get; set; }
        public DateTime LastModified { get; set; }
        public string ETag { get; set; }
        public bool IsDirectory { get; set; }
        public string ContentType { get; set; }
    }

    public class WebDavClient : IDisposable
    {
        private readonly HttpClient _httpClient;
        private readonly string _baseUri;

        public WebDavClient(string baseUri, string username = null, string password = null)
        {
            _baseUri = baseUri.TrimEnd('/') + '/';

            var handler = new HttpClientHandler
            {
                ServerCertificateCustomValidationCallback = (msg, cert, chain, errors) => true
            };

            _httpClient = new HttpClient(handler)
            {
                Timeout = TimeSpan.FromSeconds(30)
            };

            if (!string.IsNullOrEmpty(username))
            {
                var credentials = Convert.ToBase64String(
                    Encoding.UTF8.GetBytes($"{username}:{password ?? ""}"));
                _httpClient.DefaultRequestHeaders.Authorization =
                    new AuthenticationHeaderValue("Basic", credentials);
            }

            _httpClient.DefaultRequestHeaders.UserAgent.ParseAdd("Expandroid/2.0");
        }

        private const string PropfindBody = @"<?xml version=""1.0"" encoding=""utf-8""?>
<D:propfind xmlns:D=""DAV:"">
  <D:prop>
    <D:displayname/>
    <D:getcontentlength/>
    <D:getlastmodified/>
    <D:getetag/>
    <D:resourcetype/>
    <D:getcontenttype/>
  </D:prop>
</D:propfind>";

        public async Task<bool> TestConnectionAsync(CancellationToken ct = default)
        {
            try
            {
                var request = new HttpRequestMessage(new HttpMethod("PROPFIND"), _baseUri);
                request.Headers.Add("Depth", "0");
                request.Content = new StringContent(PropfindBody, Encoding.UTF8, "application/xml");

                var response = await _httpClient.SendAsync(request, ct);
                return response.IsSuccessStatusCode || (int)response.StatusCode == 207;
            }
            catch
            {
                return false;
            }
        }

        public async Task<List<WebDavFileInfo>> ListDirectoryAsync(string remotePath = "", CancellationToken ct = default)
        {
            return await PropFindAsync(remotePath, "1", ct);
        }

        public async Task<WebDavFileInfo> GetFileInfoAsync(string remotePath, CancellationToken ct = default)
        {
            var results = await PropFindAsync(remotePath, "0", ct);
            return results.FirstOrDefault();
        }

        private async Task<List<WebDavFileInfo>> PropFindAsync(string remotePath, string depth, CancellationToken ct)
        {
            var uri = BuildUri(remotePath);
            var request = new HttpRequestMessage(new HttpMethod("PROPFIND"), uri);
            request.Headers.Add("Depth", depth);

            request.Content = new StringContent(PropfindBody, Encoding.UTF8, "application/xml");

            var response = await _httpClient.SendAsync(request, ct);
            if (!response.IsSuccessStatusCode && (int)response.StatusCode != 207)
                throw new WebDavException($"PROPFIND failed: {response.StatusCode}");

            var content = await response.Content.ReadAsStringAsync(ct);
            return ParsePropFindResponse(content);
        }

        public async Task<string> GetFileAsync(string remotePath, CancellationToken ct = default)
        {
            var uri = BuildUri(remotePath);
            var response = await _httpClient.GetAsync(uri, ct);
            response.EnsureSuccessStatusCode();
            return await response.Content.ReadAsStringAsync(ct);
        }

        public async Task<bool> PutFileAsync(string remotePath, string content, CancellationToken ct = default)
        {
            var uri = BuildUri(remotePath);
            var request = new HttpRequestMessage(HttpMethod.Put, uri)
            {
                Content = new StringContent(content, Encoding.UTF8, "text/yaml")
            };

            var response = await _httpClient.SendAsync(request, ct);
            return response.IsSuccessStatusCode || (int)response.StatusCode == 204;
        }

        public async Task<bool> DeleteFileAsync(string remotePath, CancellationToken ct = default)
        {
            var uri = BuildUri(remotePath);
            var request = new HttpRequestMessage(HttpMethod.Delete, uri);
            var response = await _httpClient.SendAsync(request, ct);
            return response.IsSuccessStatusCode || (int)response.StatusCode == 204;
        }

        public async Task<bool> MkcolAsync(string remotePath, CancellationToken ct = default)
        {
            var uri = BuildUri(remotePath);
            var request = new HttpRequestMessage(new HttpMethod("MKCOL"), uri);
            var response = await _httpClient.SendAsync(request, ct);
            return response.IsSuccessStatusCode || (int)response.StatusCode == 201;
        }

        public async Task<string> GetETagAsync(string remotePath, CancellationToken ct = default)
        {
            var info = await GetFileInfoAsync(remotePath, ct);
            return info?.ETag;
        }

        private string BuildUri(string remotePath)
        {
            if (string.IsNullOrEmpty(remotePath))
                return _baseUri;
            remotePath = remotePath.TrimStart('/');
            return _baseUri + remotePath;
        }

        private static string GetLastSegment(string path)
        {
            if (string.IsNullOrEmpty(path)) return "";
            var trimmed = path.TrimEnd('/').TrimEnd('\\');
            var idx = Math.Max(trimmed.LastIndexOf('/'), trimmed.LastIndexOf('\\'));
            return idx < 0 ? trimmed : trimmed.Substring(idx + 1);
        }

        private static List<WebDavFileInfo> ParsePropFindResponse(string xml)
        {
            var result = new List<WebDavFileInfo>();
            try
            {
                var doc = XDocument.Parse(xml);

                XNamespace dav = "DAV:";

                var responses = doc.Descendants(dav + "response");
                foreach (var resp in responses)
                {
                    var info = new WebDavFileInfo();

                    var hrefElem = resp.Element(dav + "href");
                    if (hrefElem != null)
                        info.Href = Uri.UnescapeDataString(hrefElem.Value);

                    var propstat = resp.Element(dav + "propstat");
                    if (propstat == null) continue;
                    var prop = propstat.Element(dav + "prop");
                    if (prop == null) continue;

                    var displayName = prop.Element(dav + "displayname");
                    if (displayName != null)
                        info.DisplayName = displayName.Value;

                    var contentLength = prop.Element(dav + "getcontentlength");
                    if (contentLength != null && long.TryParse(contentLength.Value, out var size))
                        info.Size = size;

                    var lastModified = prop.Element(dav + "getlastmodified");
                    if (lastModified != null && DateTime.TryParse(lastModified.Value, out var dt))
                        info.LastModified = dt;

                    var etag = prop.Element(dav + "getetag");
                    if (etag != null)
                        info.ETag = etag.Value.Trim('"');

                    var resourceType = prop.Element(dav + "resourcetype");
                    info.IsDirectory = resourceType?.Element(dav + "collection") != null;

                    var contentType = prop.Element(dav + "getcontenttype");
                    if (contentType != null)
                        info.ContentType = contentType.Value;

                    if (string.IsNullOrEmpty(info.DisplayName))
                        info.DisplayName = GetLastSegment(info.Href);

                    result.Add(info);
                }
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"ParsePropFindResponse failed: {ex.Message}");
            }

            return result;
        }

        public void Dispose()
        {
            _httpClient?.Dispose();
        }
    }

    public class WebDavException : Exception
    {
        public WebDavException(string message) : base(message) { }
        public WebDavException(string message, Exception inner) : base(message, inner) { }
    }
}

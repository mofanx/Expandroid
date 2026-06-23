#if ANDROID
using Android.Content;
using Android.Database;
using Android.Provider;
using Android.Webkit;
using Microsoft.Maui.Storage;
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading.Tasks;

namespace Expandroid.Services
{
    public class SafManager
    {
        private readonly Context _context;

        public SafManager(Context context)
        {
            _context = context;
        }

        public struct SafFileInfo
        {
            public string Uri { get; set; }
            public long Size { get; set; }
            public long LastModified { get; set; }
        }

        public bool HasPersistableUriPermission(string uriString)
        {
            try
            {
                var uri = Android.Net.Uri.Parse(uriString);
                var perms = _context.ContentResolver.PersistedUriPermissions;
                return perms.Any(p => p.Uri.ToString() == uriString);
            }
            catch
            {
                return false;
            }
        }

        public void TakePersistableUriPermission(string uriString)
        {
            try
            {
                var uri = Android.Net.Uri.Parse(uriString);
                _context.ContentResolver.TakePersistableUriPermission(
                    uri,
                    ActivityFlags.GrantReadUriPermission | ActivityFlags.GrantWriteUriPermission
                );
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"TakePersistableUriPermission failed: {ex.Message}");
            }
        }

        public void ReleasePersistableUriPermission(string uriString)
        {
            try
            {
                var uri = Android.Net.Uri.Parse(uriString);
                _context.ContentResolver.ReleasePersistableUriPermission(
                    uri,
                    ActivityFlags.GrantReadUriPermission | ActivityFlags.GrantWriteUriPermission
                );
            }
            catch { }
        }

        public string GetRealPathFromUri(string uriString)
        {
            try
            {
                var uri = Android.Net.Uri.Parse(uriString);
                if (uri.Scheme == "file")
                    return uri.Path;

                var docId = DocumentsContract.GetTreeDocumentId(uri);
                var split = docId.Split(':');
                if (split.Length < 2)
                    return null;

                var type = split[0];
                var relativePath = split[1];

                if (type == "primary")
                    return Path.Combine(Android.OS.Environment.ExternalStorageDirectory.AbsolutePath, relativePath);

                return null;
            }
            catch
            {
                return null;
            }
        }

        public List<string> ListYamlFiles(string treeUriString)
        {
            return ListYamlFilesWithMetadata(treeUriString).Select(f => f.Uri).ToList();
        }

        public List<SafFileInfo> ListYamlFilesWithMetadata(string treeUriString)
        {
            var result = new List<SafFileInfo>();
            try
            {
                var treeUri = Android.Net.Uri.Parse(treeUriString);
                var childrenUri = DocumentsContract.BuildChildDocumentsUriUsingTree(
                    treeUri, DocumentsContract.GetTreeDocumentId(treeUri));

                var columns = new string[]
                {
                    DocumentsContract.Document.ColumnDocumentId,
                    DocumentsContract.Document.ColumnMimeType,
                    DocumentsContract.Document.ColumnDisplayName,
                    DocumentsContract.Document.ColumnSize,
                    DocumentsContract.Document.ColumnLastModified
                };

                using var cursor = _context.ContentResolver.Query(
                    childrenUri, columns, null, null, null);

                if (cursor != null)
                {
                    while (cursor.MoveToNext())
                    {
                        var docId = cursor.GetString(0);
                        var mimeType = cursor.GetString(1);
                        var name = cursor.GetString(2);

                        if (name == null) continue;

                        if (mimeType == DocumentsContract.Document.MimeTypeDir)
                        {
                            var subTreeUri = DocumentsContract.BuildDocumentUriUsingTree(treeUri, docId);
                            result.AddRange(ListYamlFilesWithMetadataRecursive(treeUri, subTreeUri.ToString()));
                        }
                        else if (name.EndsWith(".yml") || name.EndsWith(".yaml"))
                        {
                            if (!name.StartsWith("_"))
                            {
                                var fileUri = DocumentsContract.BuildDocumentUriUsingTree(treeUri, docId);
                                long size = 0, lastMod = 0;
                                try { size = cursor.GetLong(3); } catch { }
                                try { lastMod = cursor.GetLong(4); } catch { }
                                result.Add(new SafFileInfo
                                {
                                    Uri = fileUri.ToString(),
                                    Size = size,
                                    LastModified = lastMod
                                });
                            }
                        }
                    }
                }
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"ListYamlFilesWithMetadata failed: {ex.Message}");
            }
            return result;
        }

        private List<SafFileInfo> ListYamlFilesWithMetadataRecursive(Android.Net.Uri treeUri, string folderUriString)
        {
            var result = new List<SafFileInfo>();
            try
            {
                var folderUri = Android.Net.Uri.Parse(folderUriString);
                var childrenUri = DocumentsContract.BuildChildDocumentsUriUsingTree(
                    treeUri, DocumentsContract.GetDocumentId(folderUri));

                var columns = new string[]
                {
                    DocumentsContract.Document.ColumnDocumentId,
                    DocumentsContract.Document.ColumnMimeType,
                    DocumentsContract.Document.ColumnDisplayName,
                    DocumentsContract.Document.ColumnSize,
                    DocumentsContract.Document.ColumnLastModified
                };

                using var cursor = _context.ContentResolver.Query(
                    childrenUri, columns, null, null, null);

                if (cursor != null)
                {
                    while (cursor.MoveToNext())
                    {
                        var docId = cursor.GetString(0);
                        var mimeType = cursor.GetString(1);
                        var name = cursor.GetString(2);

                        if (name == null) continue;

                        if (mimeType == DocumentsContract.Document.MimeTypeDir)
                        {
                            var subUri = DocumentsContract.BuildDocumentUriUsingTree(treeUri, docId);
                            result.AddRange(ListYamlFilesWithMetadataRecursive(treeUri, subUri.ToString()));
                        }
                        else if (name.EndsWith(".yml") || name.EndsWith(".yaml"))
                        {
                            if (!name.StartsWith("_"))
                            {
                                var fileUri = DocumentsContract.BuildDocumentUriUsingTree(treeUri, docId);
                                long size = 0, lastMod = 0;
                                try { size = cursor.GetLong(3); } catch { }
                                try { lastMod = cursor.GetLong(4); } catch { }
                                result.Add(new SafFileInfo
                                {
                                    Uri = fileUri.ToString(),
                                    Size = size,
                                    LastModified = lastMod
                                });
                            }
                        }
                    }
                }
            }
            catch { }
            return result;
        }

        public async Task<string> ReadFileAsync(string fileUriString)
        {
            try
            {
                var uri = Android.Net.Uri.Parse(fileUriString);
                using var stream = _context.ContentResolver.OpenInputStream(uri);
                using var reader = new StreamReader(stream);
                return await reader.ReadToEndAsync();
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"ReadFileAsync failed: {ex.Message}");
                return null;
            }
        }

        public async Task<bool> WriteFileAsync(string fileUriString, string content)
        {
            try
            {
                var uri = Android.Net.Uri.Parse(fileUriString);
                using var stream = _context.ContentResolver.OpenOutputStream(uri, "wt");
                using var writer = new StreamWriter(stream);
                await writer.WriteAsync(content);
                return true;
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"WriteFileAsync failed: {ex.Message}");
                return false;
            }
        }

        public string ComputeHash(string fileUriString)
        {
            try
            {
                var uri = Android.Net.Uri.Parse(fileUriString);
                using var stream = _context.ContentResolver.OpenInputStream(uri);
                using var md5 = System.Security.Cryptography.MD5.Create();
                var hash = md5.ComputeHash(stream);
                return BitConverter.ToString(hash).Replace("-", "").ToLowerInvariant();
            }
            catch
            {
                return null;
            }
        }

        public bool FileExists(string fileUriString)
        {
            try
            {
                var uri = Android.Net.Uri.Parse(fileUriString);
                using var cursor = _context.ContentResolver.Query(
                    uri, new string[] { DocumentsContract.Document.ColumnSize }, null, null, null);
                return cursor != null && cursor.MoveToFirst();
            }
            catch
            {
                return false;
            }
        }

        public bool DeleteFileAsync(string fileUriString)
        {
            try
            {
                var uri = Android.Net.Uri.Parse(fileUriString);
                return DocumentsContract.DeleteDocument(_context.ContentResolver, uri);
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"DeleteFileAsync failed: {ex.Message}");
                return false;
            }
        }

        public string CreateDocumentUri(string treeUriString, string displayName)
        {
            try
            {
                var treeUri = Android.Net.Uri.Parse(treeUriString);
                var parentDocId = DocumentsContract.GetTreeDocumentId(treeUri);
                var parentDocUri = DocumentsContract.BuildDocumentUriUsingTree(treeUri, parentDocId);
                var newUri = DocumentsContract.CreateDocument(_context.ContentResolver, parentDocUri, "application/octet-stream", displayName);
                return newUri?.ToString();
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"CreateDocumentUri failed: {ex.Message}");
                return null;
            }
        }

        public async Task<bool> WriteTextFileAsync(string treeUriString, string fileName, string content)
        {
            try
            {
                var treeUri = Android.Net.Uri.Parse(treeUriString);
                var parentDocId = DocumentsContract.GetTreeDocumentId(treeUri);
                var parentDocUri = DocumentsContract.BuildDocumentUriUsingTree(treeUri, parentDocId);

                var existing = ListYamlFiles(treeUriString)
                    .FirstOrDefault(u => u.EndsWith("/" + fileName));

                string docUri;
                if (existing != null)
                {
                    docUri = existing;
                }
                else
                {
                    var newUri = DocumentsContract.CreateDocument(
                        _context.ContentResolver, parentDocUri, "application/octet-stream", fileName);
                    docUri = newUri?.ToString();
                }

                if (docUri == null) return false;
                return await WriteFileAsync(docUri, content);
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"WriteTextFileAsync failed: {ex.Message}");
                return false;
            }
        }

        public void RegisterContentObserver(Android.Net.Uri uri, bool notifyForDescendants, ContentObserver observer)
        {
            try
            {
                _context.ContentResolver.RegisterContentObserver(uri, notifyForDescendants, observer);
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"RegisterContentObserver failed: {ex.Message}");
            }
        }

        public void UnregisterContentObserver(ContentObserver observer)
        {
            try
            {
                _context.ContentResolver.UnregisterContentObserver(observer);
            }
            catch { }
        }
    }
}
#endif

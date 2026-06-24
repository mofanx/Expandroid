#if ANDROID
using Android.Database;
using Android.OS;
using Expandroid.Models;
using System;
using System.Threading;
using System.Threading.Tasks;

namespace Expandroid.Services
{
    public class SafObserver : ContentObserver
    {
        private readonly SafManager _safManager;
        private readonly string _treeUri;
        private readonly SyncManager _syncManager;
        private Timer _pollTimer;
        private readonly int _pollIntervalSec;
        private DateTime _lastCheck;
        private bool _disposed;

        public event Action OnRemoteChanged;

        public SafObserver(SafManager safManager, SyncManager syncManager, string treeUri, int pollIntervalSec = 60)
            : base(new Handler(Looper.MainLooper))
        {
            _safManager = safManager;
            _syncManager = syncManager;
            _treeUri = treeUri;
            _pollIntervalSec = pollIntervalSec;
            _lastCheck = DateTime.UtcNow;
        }

        public void Start()
        {
            try
            {
                var uri = Android.Net.Uri.Parse(_treeUri);
                _safManager.RegisterContentObserver(uri, true, this);
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"SafObserver Start failed: {ex.Message}");
            }

            _pollTimer = new Timer(PollCallback, null, TimeSpan.FromSeconds(_pollIntervalSec), TimeSpan.FromSeconds(_pollIntervalSec));
        }

        public void Stop()
        {
            try
            {
                _safManager.UnregisterContentObserver(this);
            }
            catch { }
            _pollTimer?.Dispose();
            _pollTimer = null;
        }

        public override void OnChange(bool selfChange)
        {
            OnChange(selfChange, null);
        }

        public override void OnChange(bool selfChange, Android.Net.Uri? uri)
        {
            System.Diagnostics.Debug.WriteLine($"SafObserver: ContentObserver triggered for {uri}");
            _ = TriggerSyncAsync();
        }

        private void PollCallback(object state)
        {
            if (_disposed) return;
            _ = TriggerPollAsync();
        }

        private async Task TriggerPollAsync()
        {
            try
            {
                if (_syncManager == null) return;
                var hasChanges = _syncManager.CheckChanges();
                if (hasChanges)
                {
                    System.Diagnostics.Debug.WriteLine("SafObserver: Poll detected remote changes");
                    OnRemoteChanged?.Invoke();
                }
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"SafObserver poll failed: {ex.Message}");
            }
        }

        private async Task TriggerSyncAsync()
        {
            try
            {
                await Task.Delay(500);
                if (_syncManager == null) return;
                var hasChanges = _syncManager.CheckChanges();
                if (hasChanges)
                {
                    System.Diagnostics.Debug.WriteLine("SafObserver: ContentObserver detected remote changes");
                    OnRemoteChanged?.Invoke();
                }
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"SafObserver sync trigger failed: {ex.Message}");
            }
        }

        protected override void Dispose(bool disposing)
        {
            if (!_disposed)
            {
                _disposed = true;
                Stop();
            }
            base.Dispose(disposing);
        }
    }
}
#endif

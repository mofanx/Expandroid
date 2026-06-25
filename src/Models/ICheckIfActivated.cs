namespace EspansoGo.Models
{
    internal interface ICheckIfActivated
    {
        public bool IsActivated();
        public void OpenSettings();
        public bool RequestPermission();

        public bool IsShizukuAvailable();
        public bool IsShizukuAuthorized();
        public Task<bool> RequestShizukuAuthorization();
        public Task<bool> TryEnableAccessibility();
    }
}

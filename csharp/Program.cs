using System.Text;
using SwimSmimeDemo;

var mode = args.Length > 0 ? args[0] : "rest-consumer";
var certsDir = args.Length > 1 ? args[1] : Path.Combine("..", "certs");

switch (mode)
{
    case "rest-consumer":
        await RunRestConsumer(certsDir, args);
        break;
    case "rest-producer":
        await RunRestProducer(certsDir, args);
        break;
    case "amqp-producer":
        RunAmqpProducer(certsDir, args);
        break;
    case "amqp-consumer":
        RunAmqpConsumer(certsDir, args);
        break;
    case "test":
        RunSelfTest(certsDir);
        break;
    default:
        Console.WriteLine("Usage: dotnet run -- <mode> [certsDir] [extra-args...]");
        Console.WriteLine("Modes: rest-consumer, rest-producer, amqp-producer, amqp-consumer, test");
        break;
}

// ──── REST Consumer (ASP.NET Minimal API) ────

static async Task RunRestConsumer(string certsDir, string[] args)
{
    int port = args.Length > 2 ? int.Parse(args[2]) : 8443;
    Console.WriteLine("=== SWIM S/MIME REST Consumer (C#) ===");
    Console.WriteLine($"Certs dir: {certsDir}");
    Console.WriteLine($"Listening on port {port}");

    var builder = WebApplication.CreateBuilder();
    builder.WebHost.UseUrls($"http://0.0.0.0:{port}");
    var app = builder.Build();

    app.MapPost("/receive", async (HttpContext ctx) =>
    {
        var contentType = ctx.Request.ContentType ?? "";
        var mimeVersion = ctx.Request.Headers["MIME-Version"].ToString();
        using var ms = new MemoryStream();
        await ctx.Request.Body.CopyToAsync(ms);
        var body = ms.ToArray();

        Console.WriteLine($"\n=== Received S/MIME Message ===");
        Console.WriteLine($"Content-Type: {contentType}");
        Console.WriteLine($"MIME-Version: {mimeVersion}");
        Console.WriteLine($"Body size: {body.Length} bytes");

        try
        {
            byte[] payload;

            if (contentType.Contains("multipart/signed"))
            {
                var signerCert = SmimeHelper.LoadCertificate(
                    Path.Combine(certsDir, "producer.crt"));
                payload = SmimeHelper.VerifySignature(body, contentType, signerCert);
                Console.WriteLine("Signature VERIFIED successfully");
            }
            else if (contentType.Contains("application/pkcs7-mime"))
            {
                var consumerCert = SmimeHelper.LoadCertificateWithKey(
                    Path.Combine(certsDir, "consumer.pfx"), "changeit");
                var signerCert = SmimeHelper.LoadCertificate(
                    Path.Combine(certsDir, "producer.crt"));
                payload = SmimeHelper.DecryptAndVerify(body, contentType,
                    consumerCert, signerCert);
                Console.WriteLine("Message DECRYPTED and signature VERIFIED");
            }
            else
            {
                ctx.Response.StatusCode = 400;
                return $"Unsupported Content-Type: {contentType}";
            }

            var payloadStr = Encoding.UTF8.GetString(payload).Trim();
            Console.WriteLine($"Extracted payload:\n{payloadStr}");
            return $"OK - Payload received and verified ({payloadStr.Length} bytes)";
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"Error: {ex.Message}");
            Console.Error.WriteLine(ex.StackTrace);
            ctx.Response.StatusCode = 500;
            return $"Error: {ex.Message}";
        }
    });

    await app.RunAsync();
}

// ──── REST Producer ────

static async Task RunRestProducer(string certsDir, string[] args)
{
    string payloadFile = args.Length > 2 ? args[2] : Path.Combine("..", "payload", "sample-flight.json");
    string targetUrl = args.Length > 3 ? args[3] : "http://localhost:5000/receive";
    string signMode = args.Length > 4 ? args[4] : "sign-encrypt";

    Console.WriteLine("=== SWIM S/MIME REST Producer (C#) ===");
    Console.WriteLine($"Mode: {signMode}");
    Console.WriteLine($"Target: {targetUrl}");

    var payload = File.ReadAllBytes(payloadFile);
    Console.WriteLine($"Payload loaded: {payload.Length} bytes");

    var signerCert = SmimeHelper.LoadCertificateWithKey(
        Path.Combine(certsDir, "producer.pfx"), "changeit");

    SmimeResult result;
    if (signMode == "sign-encrypt")
    {
        var recipientCert = SmimeHelper.LoadCertificate(
            Path.Combine(certsDir, "consumer.crt"));
        result = SmimeHelper.SignAndEncrypt(payload, "application/json",
            signerCert, recipientCert);
        Console.WriteLine("Message signed and encrypted");
    }
    else
    {
        result = SmimeHelper.Sign(payload, "application/json", signerCert);
        Console.WriteLine("Message signed");
    }

    Console.WriteLine($"S/MIME Content-Type: {result.ContentType}");

    using var client = new HttpClient();
    var request = new HttpRequestMessage(HttpMethod.Post, targetUrl);
    request.Content = new ByteArrayContent(result.Data);
    request.Content.Headers.Clear();
    request.Content.Headers.TryAddWithoutValidation("Content-Type", result.ContentType);
    request.Headers.TryAddWithoutValidation("MIME-Version", "1.0");

    var response = await client.SendAsync(request);
    Console.WriteLine($"Response status: {(int)response.StatusCode}");
    Console.WriteLine($"Response body: {await response.Content.ReadAsStringAsync()}");
}

// ──── AMQP Producer ────

static void RunAmqpProducer(string certsDir, string[] args)
{
    string payloadFile = args.Length > 2 ? args[2] : Path.Combine("..", "payload", "sample-flight.json");
    string brokerUrl = args.Length > 3 ? args[3] : "amqp://admin:admin@localhost:5672";
    string queueName = args.Length > 4 ? args[4] : "swim.flight.data";
    string signMode = args.Length > 5 ? args[5] : "sign";

    Console.WriteLine("=== SWIM S/MIME AMQP Producer (C#) ===");
    Console.WriteLine($"Mode: {signMode}");
    Console.WriteLine($"Broker: {brokerUrl}");
    Console.WriteLine($"Queue: {queueName}");

    var payload = File.ReadAllBytes(payloadFile);
    Console.WriteLine($"Payload loaded: {payload.Length} bytes");

    var signerCert = SmimeHelper.LoadCertificateWithKey(
        Path.Combine(certsDir, "producer.pfx"), "changeit");

    SmimeResult result;
    if (signMode == "sign-encrypt")
    {
        var recipientCert = SmimeHelper.LoadCertificate(
            Path.Combine(certsDir, "consumer.crt"));
        result = SmimeHelper.SignAndEncrypt(payload, "application/json",
            signerCert, recipientCert);
        Console.WriteLine("Message signed and encrypted");
    }
    else
    {
        result = SmimeHelper.Sign(payload, "application/json", signerCert);
        Console.WriteLine("Message signed");
    }

    Console.WriteLine($"S/MIME Content-Type: {result.ContentType}");

    var address = new Amqp.Address(brokerUrl);
    var connection = new Amqp.Connection(address);
    var session = new Amqp.Session(connection);
    var sender = new Amqp.SenderLink(session, "swim-producer", queueName);

    var message = new Amqp.Message(result.Data);
    message.Properties = new Amqp.Framing.Properties
    {
        ContentType = result.ContentType
    };
    // SWIM-TIYP-0112: MIME-Version as application property (with hyphen per spec)
    message.ApplicationProperties = new Amqp.Framing.ApplicationProperties();
    message.ApplicationProperties["MIME-Version"] = "1.0";

    sender.Send(message);
    Console.WriteLine($"Message sent to AMQP queue: {queueName}");

    sender.Close();
    session.Close();
    connection.Close();
}

// ──── AMQP Consumer ────

static void RunAmqpConsumer(string certsDir, string[] args)
{
    string brokerUrl = args.Length > 2 ? args[2] : "amqp://admin:admin@localhost:5672";
    string queueName = args.Length > 3 ? args[3] : "swim.flight.data";

    Console.WriteLine("=== SWIM S/MIME AMQP Consumer (C#) ===");
    Console.WriteLine($"Broker: {brokerUrl}");
    Console.WriteLine($"Queue: {queueName}");
    Console.WriteLine("Waiting for messages... (press Ctrl+C to exit)");

    var consumerCert = SmimeHelper.LoadCertificateWithKey(
        Path.Combine(certsDir, "consumer.pfx"), "changeit");
    var signerCert = SmimeHelper.LoadCertificate(
        Path.Combine(certsDir, "producer.crt"));

    var address = new Amqp.Address(brokerUrl);
    var connection = new Amqp.Connection(address);
    var session = new Amqp.Session(connection);
    var receiver = new Amqp.ReceiverLink(session, "swim-consumer", queueName);

    receiver.Start(200, (link, msg) =>
    {
        Console.WriteLine($"\n=== Received AMQP Message ===");
        string contentType = msg.Properties?.ContentType?.ToString() ?? "";
        string mimeVersion = msg.ApplicationProperties?["MIME-Version"]?.ToString() ?? "";
        Console.WriteLine($"Content-Type: {contentType}");
        Console.WriteLine($"MIME-Version: {mimeVersion}");

        byte[] body = msg.Body is byte[] bytes ? bytes : Encoding.UTF8.GetBytes(msg.Body?.ToString() ?? "");
        Console.WriteLine($"Body size: {body.Length} bytes");

        try
        {
            byte[] payload;
            if (contentType.Contains("smime-type=signed-data", StringComparison.OrdinalIgnoreCase))
            {
                payload = SmimeHelper.VerifySignature(body, contentType, signerCert);
                Console.WriteLine("Signature VERIFIED successfully");
            }
            else if (contentType.Contains("smime-type=enveloped-data", StringComparison.OrdinalIgnoreCase))
            {
                payload = SmimeHelper.DecryptAndVerify(body, contentType,
                    consumerCert, signerCert);
                Console.WriteLine("Message DECRYPTED and signature VERIFIED");
            }
            else
            {
                Console.Error.WriteLine($"Unsupported Content-Type: {contentType}");
                link.Accept(msg);
                return;
            }

            Console.WriteLine($"Extracted payload:\n{Encoding.UTF8.GetString(payload).Trim()}");
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"Error: {ex.Message}");
            Console.Error.WriteLine(ex.StackTrace);
        }

        link.Accept(msg);
    });

    Thread.Sleep(Timeout.Infinite);
}

// ──── Self Test ────

static void RunSelfTest(string certsDir)
{
    Console.WriteLine("=== C# S/MIME Self Test ===");

    var signerCert = SmimeHelper.LoadCertificateWithKey(
        Path.Combine(certsDir, "producer.pfx"), "changeit");
    var recipientCert = SmimeHelper.LoadCertificateWithKey(
        Path.Combine(certsDir, "consumer.pfx"), "changeit");
    var recipientPub = SmimeHelper.LoadCertificate(
        Path.Combine(certsDir, "consumer.crt"));

    byte[] payload = Encoding.UTF8.GetBytes("{\"test\": \"hello\"}");

    Console.Write("SIGN... ");
    var signed = SmimeHelper.Sign(payload, "application/json", signerCert);
    Console.WriteLine($"OK ({signed.Data.Length} bytes)");

    Console.Write("VERIFY... ");
    var verified = SmimeHelper.VerifySignature(signed.Data, signed.ContentType, signerCert);
    Console.WriteLine($"OK ({Encoding.UTF8.GetString(verified)})");

    Console.Write("ENCRYPT... ");
    var encrypted = SmimeHelper.Encrypt(payload, "application/json", recipientPub);
    Console.WriteLine($"OK ({encrypted.Data.Length} bytes)");

    Console.Write("DECRYPT... ");
    var decrypted = SmimeHelper.Decrypt(encrypted.Data, encrypted.ContentType, recipientCert);
    Console.WriteLine($"OK ({Encoding.UTF8.GetString(decrypted)})");

    Console.Write("SIGN+ENCRYPT... ");
    var se = SmimeHelper.SignAndEncrypt(payload, "application/json", signerCert, recipientPub);
    Console.WriteLine($"OK ({se.Data.Length} bytes)");

    Console.Write("DECRYPT+VERIFY... ");
    var dv = SmimeHelper.DecryptAndVerify(se.Data, se.ContentType, recipientCert, signerCert);
    Console.WriteLine($"OK ({Encoding.UTF8.GetString(dv)})");

    Console.WriteLine("ALL C# S/MIME TESTS PASSED");
}

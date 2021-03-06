package emented.lab8FX.server;


import emented.lab8FX.common.util.RequestType;
import emented.lab8FX.common.util.TextColoring;
import emented.lab8FX.server.db.DBSSHConnector;
import emented.lab8FX.server.interfaces.SocketWorkerInterface;
import emented.lab8FX.server.util.CommandManager;
import emented.lab8FX.server.util.RequestWithAddress;
import emented.lab8FX.server.util.UsersManager;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;


public class RequestThread implements Runnable {

    private final SocketWorkerInterface serverSocketWorker;
    private final CommandManager commandManager;
    private final UsersManager usersManager;
    private final ExecutorService fixedService = Executors.newFixedThreadPool(5);
    private final ExecutorService cachedService = Executors.newCachedThreadPool();
    private final ForkJoinPool forkJoinPool = new ForkJoinPool(4);

    public RequestThread(SocketWorkerInterface serverSocketWorker, CommandManager commandManager, UsersManager usersManager) {
        this.serverSocketWorker = serverSocketWorker;
        this.commandManager = commandManager;
        this.usersManager = usersManager;
    }

    @Override
    public void run() {
        while (ServerConfig.getRunning()) {
            try {
                Future<RequestWithAddress> listenFuture = fixedService.submit(serverSocketWorker::listenForRequest);
                RequestWithAddress acceptedRequest = listenFuture.get();
                if (acceptedRequest != null) {
                    CompletableFuture
                            .supplyAsync(acceptedRequest::getRequest)
                            .thenApplyAsync(request -> {
                                if (request.getRequestType().equals(RequestType.COMMAND)) {
                                    return commandManager.executeClientCommand(request);
                                } else if (request.getRequestType().equals(RequestType.REGISTER)) {
                                    return usersManager.registerNewUser(request);
                                } else {
                                    return usersManager.loginUser(request);
                                }
                            }, cachedService)
                            .thenAcceptAsync(response -> {
                                try {
                                    serverSocketWorker.sendResponse(response, acceptedRequest.getSocketAddress());
                                } catch (IOException e) {
                                    ServerConfig.getConsoleTextPrinter().printlnText(TextColoring.getRedText(e.getMessage()));
                                }
                            }, forkJoinPool);
                }
            } catch (ExecutionException e) {
                ServerConfig.getConsoleTextPrinter().printlnText(TextColoring.getRedText(e.getMessage()));
            } catch (InterruptedException e) {
                ServerConfig.getConsoleTextPrinter().printlnText(TextColoring.getRedText("An error occurred while deserializing the request, try again"));
            }
        }
        try {
            serverSocketWorker.stopSocketWorker();
            DBSSHConnector.closeSSH();
            fixedService.shutdown();
            cachedService.shutdown();
            forkJoinPool.shutdown();
        } catch (IOException e) {
            ServerConfig.getConsoleTextPrinter().printlnText(TextColoring.getRedText("An error occurred during stopping the server"));
        }
    }
}

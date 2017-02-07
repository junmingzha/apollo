package io.logz.apollo.controllers;

import io.logz.apollo.LockService;
import io.logz.apollo.auth.PermissionsValidator;
import io.logz.apollo.dao.DeploymentDao;
import io.logz.apollo.dao.DeploymentPermissionDao;
import io.logz.apollo.database.ApolloMyBatis;
import io.logz.apollo.models.Deployment;
import org.rapidoid.annotation.Controller;
import org.rapidoid.annotation.GET;
import org.rapidoid.annotation.POST;
import org.rapidoid.http.MediaType;
import org.rapidoid.http.Req;
import org.rapidoid.security.annotation.LoggedIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.List;
import java.util.Optional;

/**
 * Created by roiravhon on 1/5/17.
 */
@Controller
public class DeploymentController extends BaseController {

    private final DeploymentDao deploymentDao;
    private static final Logger logger = LoggerFactory.getLogger(DeploymentController.class);
    private final DeploymentPermissionDao deploymentPermissionDao;

    public DeploymentController() {
        this.deploymentDao = ApolloMyBatis.getDao(DeploymentDao.class);
        this.deploymentPermissionDao = ApolloMyBatis.getDao(DeploymentPermissionDao.class);
    }

    @LoggedIn
    @GET("/deployment")
    public List<Deployment> getAllDeployments() {
        return deploymentDao.getAllDeployments();
    }

    @LoggedIn
    @GET("/deployment/{id}")
    public Deployment getDeployment(int id) {
        return deploymentDao.getDeployment(id);
    }

    @LoggedIn
    @POST("/deployment")
    public void addDeployment(int environmentId, int serviceId, int deployableVersionId,
                              String userEmail, String sourceVersion, Req req) {

        MDC.put("environmentId", String.valueOf(environmentId));
        MDC.put("serviceId", String.valueOf(serviceId));
        MDC.put("deployableVersionId", String.valueOf(deployableVersionId));
        MDC.put("userEmail", userEmail);
        MDC.put("sourceVersion", sourceVersion);

        logger.info("Got request for a new deployment");

        if (! PermissionsValidator.isAllowedToDeploy(serviceId, environmentId,
                deploymentPermissionDao.getPermissionsByUser(userEmail))) {

            logger.info("User is not authorized to perform this deployment!");
            assignJsonResponseToReq(req, 403, "Not Authorized!");

        } else {

            String lockName = LockService.getDeploymentLockName(serviceId, environmentId);
            if (LockService.getAndObtainLock(lockName)) {
                try {
                    logger.info("Permissions verified, Checking that no other deployment is currently running");

                    DeploymentDao deploymentDao = ApolloMyBatis.getDao(DeploymentDao.class);
                    Optional<Deployment> runningDeployment = deploymentDao.getAllRunningDeployments()
                            .stream()
                            .filter(deployment ->
                                    deployment.getServiceId() == serviceId &&
                                    deployment.getEnvironmentId() == environmentId)
                            .findAny();

                    if (runningDeployment.isPresent()) {
                        logger.warn("There is already a running deployment that initiated by {}. Can't start a new one",
                                runningDeployment.get().getUserEmail());

                        assignJsonResponseToReq(req, 409, "There is an on-going deployment for this service in this environment");;
                        return;
                    }

                    logger.info("All checks passed. Running deployment");

                    Deployment newDeployment = new Deployment();
                    newDeployment.setEnvironmentId(environmentId);
                    newDeployment.setServiceId(serviceId);
                    newDeployment.setDeployableVersionId(deployableVersionId);
                    newDeployment.setUserEmail(userEmail);
                    newDeployment.setStatus(Deployment.DeploymentStatus.PENDING);
                    newDeployment.setSourceVersion(sourceVersion);

                    deploymentDao.addDeployment(newDeployment);
                    assignJsonResponseToReq(req, 201, newDeployment);
                } finally {
                    LockService.releaseLock(lockName);
                }

            } else {
                logger.warn("A deployment request of this sort is currently being running");
                assignJsonResponseToReq(req, 429, "A deployment request is currently running for this service and environment! Wait until its done");
            }
        }
    }
}
FROM kong:latest

COPY kong.yml /etc/kong/kong.yml

USER root
RUN chmod 777 /etc/kong/kong.yml

USER kong